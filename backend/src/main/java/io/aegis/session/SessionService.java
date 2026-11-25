package io.aegis.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.agent.AgentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Session metadata, chat history and the bridge to agentscope HarnessAgent. */
@Service
public class SessionService {

    public record Message(String role, String content, String toolCalls, String createdAt) {}
    public record Session(String id, String agentId, Long userId, String title, String permissionMode,
                          String createdAt, String updatedAt) {}
    /** One executed tool call, captured from the event stream (DSH-style trajectory record). */
    public record ToolCallRecord(String name, String args, String result, long startedAt,
                                 long durationMs, boolean error) {}
    /** Result of one chat turn: assistant text, tool names (simple), structured trajectory JSON, wall duration. */
    public record ChatResult(String reply, List<String> toolCalls, String toolCallsJson, long durationMs) {}

    /** Streams one chat turn to the client as it happens (text deltas, thinking, tool activity). */
    public interface ChatStreamListener {
        default void onDelta(String text) {}
        default void onThinkingDelta(String text) {}
        default void onTool(String toolName) {}
        /** Fine-grained tool lifecycle for the live trajectory view. */
        default void onToolStart(String id, String name) {}
        default void onToolArgs(String id, String name, String delta) {}
        default void onToolResult(String id, String name, String delta) {}
        default void onToolEnd(String id, String name, long durationMs, boolean error) {}
    }

    private final JdbcTemplate jdbc;
    private final AgentRepository agents;
    private final AgentRegistry registry;

    public SessionService(JdbcTemplate jdbc, AgentRepository agents, AgentRegistry registry) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.registry = registry;
    }

    private static final org.springframework.jdbc.core.RowMapper<Session> SESSION_MAP = (rs, n) ->
            new Session(rs.getString("id"), rs.getString("agent_id"), rs.getLong("user_id"),
                    rs.getString("title"), rs.getString("permission_mode"),
                    rs.getString("created_at"), rs.getString("updated_at"));

    public List<Session> listForUser(Long userId) {
        return jdbc.query("SELECT * FROM chat_session WHERE user_id=? ORDER BY updated_at DESC", SESSION_MAP, userId);
    }

    public Optional<Session> get(String id, Long userId) {
        List<Session> r = jdbc.query("SELECT * FROM chat_session WHERE id=? AND user_id=?", SESSION_MAP, id, userId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Session create(String id, String agentId, Long userId, String title, String permissionMode) {
        jdbc.update("INSERT INTO chat_session(id,agent_id,user_id,title,permission_mode) VALUES(?,?,?,?,?)",
                id, agentId, userId, title == null ? "New chat" : title,
                permissionMode == null ? "ACCEPT_EDITS" : permissionMode);
        return get(id, userId).orElseThrow();
    }

    public int setPermissionMode(String id, Long userId, String mode) {
        return jdbc.update("UPDATE chat_session SET permission_mode=? WHERE id=? AND user_id=?", mode, id, userId);
    }

    public List<Message> messages(String sessionId) {
        return jdbc.query("SELECT role,content,tool_calls,created_at FROM chat_message WHERE session_id=? ORDER BY id",
                (rs, n) -> new Message(rs.getString("role"), rs.getString("content"),
                        rs.getString("tool_calls"), rs.getString("created_at")), sessionId);
    }

    public void saveMessage(String sessionId, String role, String content, String toolCalls) {
        jdbc.update("INSERT INTO chat_message(session_id,role,content,tool_calls) VALUES(?,?,?,?)",
                sessionId, role, content, toolCalls);
        jdbc.update("UPDATE chat_session SET updated_at=datetime('now') WHERE id=?", sessionId);
    }

    /**
     * Calls the agentscope agent. Uses RuntimeContext with userId+sessionId so
     * harness isolates runtime data by (user,session) and applies permission mode.
     */
    public ChatResult chat(Session session, Long userId, String text) {
        return chat(session, userId, text, new ChatStreamListener() {});
    }

    /** Streaming variant: forwards text deltas / thinking / tool events to the listener live. */
    public ChatResult chat(Session session, Long userId, String text, ChatStreamListener listener) {
        HarnessAgent agent = registry.get(session.agentId());
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(session.id())
                .build();
        // apply permission mode from session
        try {
            io.agentscope.core.permission.PermissionMode mode =
                    io.agentscope.core.permission.PermissionMode.fromString(session.permissionMode().toLowerCase());
            agent.setPermissionMode(ctx, mode);
        } catch (Exception e) {
            // ignore invalid mode, harness default applies
        }
        Msg current = Msg.builder().textContent(text).build();
        long t0 = System.currentTimeMillis();
        List<ToolCallRecord> records = new ArrayList<>();
        Msg result = runWithAutoApprove(agent, current, ctx, records, listener);
        long durationMs = System.currentTimeMillis() - t0;
        String reply = result == null ? "" : result.getTextContent();
        List<String> names = records.stream().map(ToolCallRecord::name).toList();
        return new ChatResult(reply, names, toolCallsJson(records, durationMs), durationMs);
    }

    /** Serializes tool call records + wall duration into the JSON stored on the assistant message. */
    private String toolCallsJson(List<ToolCallRecord> records, long durationMs) {
        if (records.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolCallRecord r : records) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.name());
                m.put("args", truncate(r.args()));
                m.put("result", truncate(r.result()));
                m.put("startedAt", r.startedAt());
                m.put("durationMs", r.durationMs());
                m.put("error", r.error());
                tools.add(m);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tools", tools);
            root.put("durationMs", durationMs);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 8000 ? s : s.substring(0, 8000) + "…(truncated)";
    }

    /** Mutable accumulator for one in-flight tool call (keyed by toolCallId). */
    private static class CallAcc {
        final String name;
        final long startedAt = System.currentTimeMillis();
        final StringBuilder args = new StringBuilder();
        final StringBuilder result = new StringBuilder();
        /** Text chunks streamed via ToolProgressEvent (some tools stream output as progress). */
        final StringBuilder progress = new StringBuilder();
        Long endedAt;
        boolean error;

        CallAcc(String name) { this.name = name; }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SessionService.class);

    /**
     * Drives a synchronous conversation via the event stream, auto-approving tool calls that land
     * in PERMISSION_ASKING. Mirrors agentscope's PlanModeAutoExample. Event stream (not blocking
     * call()) is used so the full tool-call lifecycle (name, args, result, duration, error) can be
     * captured for the DSH-style trajectory view. Fits Aegis' three tiers: EXPLORE denies writes
     * at engine level (no ASKING), while ACCEPT_EDITS / BYPASS auto-approve ASKING so writes proceed.
     */
    private Msg runWithAutoApprove(HarnessAgent agent, Msg userMsg, RuntimeContext ctx,
                                   List<ToolCallRecord> executedTools, ChatStreamListener listener) {
        Msg current = userMsg;
        final int maxRounds = 12;
        Map<String, CallAcc> pendingCalls = new LinkedHashMap<>();
        for (int round = 0; round < maxRounds; round++) {
            Msg finalResult = null;
            RequireUserConfirmEvent pendingConfirm = null;
            // Blocking iteration keeps all event forwarding on the caller (servlet) thread,
            // which makes synchronous SSE writes safe and ordered.
            java.util.Iterator<AgentEvent> events = agent.streamEvents(current, ctx).toIterable().iterator();
            while (events.hasNext()) {
                AgentEvent event = events.next();
                if (event instanceof AgentResultEvent are && are.getResult() != null) {
                    finalResult = are.getResult();
                } else if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                    listener.onDelta(delta.getDelta());
                } else if (event instanceof ThinkingBlockDeltaEvent thinking && thinking.getDelta() != null) {
                    listener.onThinkingDelta(thinking.getDelta());
                } else if (event instanceof ToolCallStartEvent tc && tc.getToolCallName() != null) {
                    String id = tc.getToolCallId() == null ? tc.getToolCallName() : tc.getToolCallId();
                    pendingCalls.put(tc.getToolCallId(), new CallAcc(tc.getToolCallName()));
                    listener.onTool(tc.getToolCallName());
                    listener.onToolStart(id, tc.getToolCallName());
                } else if (event instanceof ToolCallDeltaEvent td && pendingCalls.containsKey(td.getToolCallId())
                        && td.getDelta() != null) {
                    pendingCalls.get(td.getToolCallId()).args.append(td.getDelta());
                    listener.onToolArgs(td.getToolCallId(), pendingCalls.get(td.getToolCallId()).name, td.getDelta());
                } else if (event instanceof ToolResultTextDeltaEvent trd) {
                    appendResult(pendingCalls, trd.getToolCallId(), trd.getToolCallName(), trd.getDelta(), listener);
                } else if (event instanceof ToolResultDataDeltaEvent trd && trd.getData() != null) {
                    // Non-text result blocks (e.g. execute's execution details) stream as data deltas.
                    appendResult(pendingCalls, trd.getToolCallId(), trd.getToolCallName(), blockText(trd.getData()), listener);
                } else if (event instanceof ToolProgressEvent tp && tp.getContent() != null) {
                    // Some tools stream their raw output as progress chunks; keep as fallback.
                    appendResult(pendingCalls, tp.getToolCallId(), tp.getToolCallName(), blockText(tp.getContent()), true, listener);
                } else if (event instanceof ToolResultEndEvent te) {
                    CallAcc acc = findAcc(pendingCalls, te.getToolCallId(), te.getToolCallName());
                    if (acc != null) {
                        acc.endedAt = System.currentTimeMillis();
                        acc.error = te.getState() != null && te.getState().name().contains("ERROR");
                        // Last resort: no result deltas arrived but progress chunks did.
                        if (acc.result.length() == 0 && acc.progress.length() > 0) {
                            acc.result.append(acc.progress);
                        }
                        if (acc.result.length() == 0) {
                            log.warn("Tool result captured empty after end: name={}, id={}, state={}",
                                    te.getToolCallName(), te.getToolCallId(), te.getState());
                        }
                        listener.onToolEnd(te.getToolCallId() == null ? acc.name : te.getToolCallId(),
                                acc.name, acc.endedAt - acc.startedAt, acc.error);
                    }
                } else if (event instanceof RequireUserConfirmEvent confirm) {
                    pendingConfirm = confirm;
                }
            }
            // harvest completed calls; keep unfinished ones pending — auto-approved
            // writes (PERMISSION_ASKING → ConfirmResult) execute in the NEXT round,
            // so their result events arrive after pendingCalls would otherwise be cleared.
            for (Iterator<Map.Entry<String, CallAcc>> it = pendingCalls.entrySet().iterator(); it.hasNext();) {
                CallAcc acc = it.next().getValue();
                if (acc.endedAt == null) continue;
                long dur = acc.endedAt - acc.startedAt;
                executedTools.add(new ToolCallRecord(acc.name, acc.args.toString(), acc.result.toString(),
                        acc.startedAt, dur, acc.error));
                if (acc.result.isEmpty()) {
                    log.warn("Tool result captured empty: name={}, args={}", acc.name, acc.args);
                }
                it.remove();
            }

            if (pendingConfirm == null) {
                return finalResult;
            }
            // Auto-approve every pending tool call, reusing the ORIGINAL ToolUseBlock.
            List<ConfirmResult> approvals = pendingConfirm.getToolCalls().stream()
                    .map(t -> new ConfirmResult(true, t))
                    .toList();
            if (approvals.isEmpty()) {
                return finalResult;
            }
            current = UserMessage.builder()
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, approvals))
                    .build();
        }
        throw new IllegalStateException("Too many PERMISSION_ASKING rounds (possible tool loop)");
    }

    /** Appends a streamed result chunk to the matching in-flight call, tolerating id/name mismatches. */
    private void appendResult(Map<String, CallAcc> pending, String id, String name, String text,
                              ChatStreamListener listener) {
        appendResult(pending, id, name, text, false, listener);
    }

    private void appendResult(Map<String, CallAcc> pending, String id, String name, String text,
                              boolean asProgress, ChatStreamListener listener) {
        if (text == null || text.isEmpty()) return;
        CallAcc acc = findAcc(pending, id, name);
        if (acc == null) return;
        if (asProgress) acc.progress.append(text); else acc.result.append(text);
        listener.onToolResult(id == null ? acc.name : id, acc.name, text);
    }

    /** Matches a pending call by exact id first, then falls back to a unique open call with the same name. */
    private static CallAcc findAcc(Map<String, CallAcc> pending, String id, String name) {
        CallAcc acc = id != null ? pending.get(id) : null;
        if (acc != null) return acc;
        if (name == null) return null;
        CallAcc found = null;
        for (CallAcc a : pending.values()) {
            if (a.name.equals(name) && a.endedAt == null) {
                if (found != null) return null; // ambiguous, do not guess
                found = a;
            }
        }
        return found;
    }

    /** Best-effort text extraction from a streamed result block. */
    private static String blockText(ContentBlock block) {
        if (block instanceof TextBlock tb) return tb.getText();
        return null;
    }

    /** Trajectory summary derived from stored messages (tool_calls holds structured JSON). */
    public Map<String, Object> trajectory(String sessionId) {
        List<Message> msgs = messages(sessionId);
        int turns = 0, toolCalls = 0;
        long totalMs = 0;
        for (Message m : msgs) {
            if ("user".equals(m.role())) turns++;
            if (m.toolCalls() != null && !m.toolCalls().isBlank()) {
                try {
                    var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(m.toolCalls());
                    if (root.has("tools") && root.get("tools").isArray()) {
                        toolCalls += root.get("tools").size();
                    }
                    if (root.has("durationMs")) {
                        totalMs += root.get("durationMs").asLong();
                    }
                } catch (Exception ignored) {
                    toolCalls++; // legacy comma-joined format
                }
            }
        }
        Session s = jdbc.query("SELECT * FROM chat_session WHERE id=?", SESSION_MAP, sessionId).get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("turns", turns);
        result.put("toolCalls", toolCalls);
        result.put("durationMs", totalMs);
        result.put("messages", msgs.stream().map(m -> Map.<String,Object>of(
                "role", m.role(), "content", m.content() == null ? "" : m.content(),
                "toolCalls", m.toolCalls() == null ? "" : m.toolCalls(),
                "createdAt", m.createdAt() == null ? "" : m.createdAt())).toList());
        result.put("createdAt", s.createdAt());
        return result;
    }
}
