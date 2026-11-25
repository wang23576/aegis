package io.aegis.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
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
                                 long durationMs, boolean error, int stepNo) {}
    /** Result of one chat turn: text + structured steps/events for 1:1 trajectory reconstruction. */
    public record ChatResult(String reply, List<String> toolCalls, String toolCallsJson, long durationMs,
                             String stepsJson, int eventCount) {}

    /** Streams one chat turn to the client as it happens (text deltas, thinking, tool activity). */
    public interface ChatStreamListener {
        default void onDelta(String text) {}
        default void onThinkingDelta(String text) {}
        default void onTool(String toolName) {}
        /** Fine-grained tool lifecycle for the live trajectory view. */
        default void onToolStart(String id, String name, int stepNo) {}
        default void onToolArgs(String id, String name, String delta) {}
        default void onToolResult(String id, String name, String delta) {}
        default void onToolEnd(String id, String name, long durationMs, boolean error) {}
        /** One LLM invocation (DSH step) starts/ends; usage is attached at the end. */
        default void onStepStart(int stepNo, String replyId) {}
        default void onStepEnd(int stepNo, long durationMs, String usageJson) {}
        /** Non-text runtime event (permission, stop, subagent, custom, etc.). */
        default void onEvent(String type, String summary) {}
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

    /** Persists the raw event log and per-LLM-call steps for 1:1 trajectory reconstruction. */
    private void saveEvidence(String sessionId, int turnNo, List<StepAcc> steps, List<EventRec> events) {
        for (EventRec e : events) {
            jdbc.update("INSERT INTO chat_event(session_id,turn_no,seq,event_type,event_id,reply_id,tool_call_id,block_id,created_at,data) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    sessionId, turnNo, e.seq(), e.type(), e.eventId(), e.replyId(), e.toolCallId(),
                    e.blockId(), e.createdAt(), e.data());
        }
        for (StepAcc s : steps) {
            jdbc.update("INSERT INTO chat_step(session_id,turn_no,step_no,reply_id,thinking,content,tool_calls,data_blocks,usage,"
                            + "started_at,first_token_at,ended_at,duration_ms,interrupted,status) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    sessionId, turnNo, s.stepNo, s.replyId, s.thinking.toString(), s.content.toString(),
                    jsonArray(s.tools), jsonArray(s.dataBlocks), s.usage == null ? "" : json(s.usage),
                    s.startedAt, s.firstTokenAt, s.endedAt,
                    (s.endedAt == null ? 0 : s.endedAt - s.startedAt), s.interrupted ? 1 : 0,
                    s.status == null ? (s.endedAt == null ? "running" : "complete") : s.status);
        }
    }

    /** Structured steps persisted for a session (newest turn last). */
    public List<Map<String, Object>> steps(String sessionId) {
        return jdbc.query("SELECT * FROM chat_step WHERE session_id=? ORDER BY id", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turnNo", rs.getInt("turn_no")); m.put("stepNo", rs.getInt("step_no"));
            m.put("replyId", rs.getString("reply_id"));
            m.put("thinking", rs.getString("thinking")); m.put("content", rs.getString("content"));
            m.put("toolCalls", rs.getString("tool_calls")); m.put("dataBlocks", rs.getString("data_blocks"));
            m.put("usage", rs.getString("usage")); m.put("startedAt", rs.getObject("started_at"));
            m.put("firstTokenAt", rs.getObject("first_token_at")); m.put("endedAt", rs.getObject("ended_at"));
            m.put("durationMs", rs.getObject("duration_ms"));
            m.put("interrupted", rs.getInt("interrupted") != 0); m.put("status", rs.getString("status"));
            return m;
        }, sessionId);
    }

    /** Raw runtime events persisted for a session — the audit evidence behind the trajectory. */
    public List<Map<String, Object>> events(String sessionId) {
        return jdbc.query("SELECT * FROM chat_event WHERE session_id=? ORDER BY seq", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String c : List.of("turn_no", "seq", "event_type", "event_id", "reply_id", "tool_call_id", "block_id", "created_at", "data")) {
                String key = c.replace("_id", "Id").replace("_no", "No").replace("_type", "Type").replace("_at", "At");
                m.put(key, rs.getString(c));
            }
            return m;
        }, sessionId);
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
        int turnNo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=? AND role='user'",
                Integer.class, session.id());
        List<ToolCallRecord> records = new ArrayList<>();
        List<StepAcc> steps = new ArrayList<>();
        List<EventRec> events = new ArrayList<>();
        Msg result = runWithAutoApprove(agent, current, ctx, records, listener, steps, events, turnNo);
        long durationMs = System.currentTimeMillis() - t0;
        String reply = result == null ? "" : result.getTextContent();
        List<String> names = records.stream().map(ToolCallRecord::name).toList();
        String stepsJson = json(steps.stream().map(StepAcc::toMap).toList());
        saveEvidence(session.id(), turnNo, steps, events);
        return new ChatResult(reply, names, toolCallsJson(records, durationMs), durationMs,
                stepsJson, events.size());
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
                m.put("stepNo", r.stepNo());
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
        int stepNo;

        CallAcc(String name) { this.name = name; }
    }

    /** One LLM invocation: DSH step = model call + the tool executions it requested. */
    private static class StepAcc {
        final int stepNo;
        String replyId;
        final long startedAt = System.currentTimeMillis();
        Long firstTokenAt;
        Long endedAt;
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final List<Map<String, Object>> tools = new ArrayList<>();
        final List<String> dataBlocks = new ArrayList<>();
        Map<String, Object> usage;
        boolean interrupted;
        String status;

        StepAcc(int stepNo) { this.stepNo = stepNo; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepNo", stepNo); m.put("replyId", replyId == null ? "" : replyId);
            m.put("thinking", thinking.toString()); m.put("content", content.toString());
            m.put("tools", tools); m.put("dataBlocks", dataBlocks);
            m.put("usage", usage == null ? Map.of() : usage);
            m.put("startedAt", startedAt); m.put("firstTokenAt", firstTokenAt);
            m.put("endedAt", endedAt);
            m.put("durationMs", endedAt == null ? System.currentTimeMillis() - startedAt : endedAt - startedAt);
            m.put("interrupted", interrupted);
            m.put("status", status == null ? (endedAt == null ? "running" : "complete") : status);
            return m;
        }
    }

    /** Compact copy of one runtime event — kept as the audit trail behind every trajectory row. */
    public record EventRec(int seq, String type, String eventId, String replyId, String toolCallId,
                           String blockId, String createdAt, String data) {}

    private static String json(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); } catch (Exception e) { return ""; }
    }

    private static String jsonArray(List<?> value) { return json(value); }

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
                                   List<ToolCallRecord> executedTools, ChatStreamListener listener,
                                   List<StepAcc> steps, List<EventRec> events, int turnNo) {
        Msg current = userMsg;
        final int maxRounds = 12;
        Map<String, CallAcc> pendingCalls = new LinkedHashMap<>();
        // tool call id/name -> the JSON record inside its owning step, for 1:1 result backfill
        Map<String, Map<String, Object>> stepTools = new LinkedHashMap<>();
        for (int round = 0; round < maxRounds; round++) {
            Msg finalResult = null;
            RequireUserConfirmEvent pendingConfirm = null;
            StepAcc active = null;
            // Blocking iteration keeps all event forwarding on the caller (servlet) thread,
            // which makes synchronous SSE writes safe and ordered.
            java.util.Iterator<AgentEvent> stream = agent.streamEvents(current, ctx).toIterable().iterator();
            while (stream.hasNext()) {
                AgentEvent event = stream.next();
                String type = event.getType() == null ? "" : event.getType().name();
                String rId = replyIdOf(event), tId = toolIdOf(event), bId = blockIdOf(event);
                // Audit trail: every runtime event, not just the ones we render today.
                events.add(new EventRec(events.size() + 1, type, event.getId(), rId, tId, bId,
                        event.getCreatedAt(), eventData(event)));

                if (event instanceof ModelCallStartEvent) {
                    // DSH step boundary: one model invocation, one assistant record.
                    active = new StepAcc(steps.size() + 1);
                    active.replyId = rId;
                    steps.add(active);
                    listener.onStepStart(active.stepNo, rId == null ? "" : rId);
                } else if (event instanceof AgentResultEvent are && are.getResult() != null) {
                    finalResult = are.getResult();
                } else if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                    if (active != null) {
                        active.content.append(delta.getDelta());
                        if (active.firstTokenAt == null) active.firstTokenAt = System.currentTimeMillis();
                    }
                    listener.onDelta(delta.getDelta());
                } else if (event instanceof ThinkingBlockDeltaEvent thinking && thinking.getDelta() != null) {
                    if (active != null) {
                        active.thinking.append(thinking.getDelta());
                        if (active.firstTokenAt == null) active.firstTokenAt = System.currentTimeMillis();
                    }
                    listener.onThinkingDelta(thinking.getDelta());
                } else if (event instanceof DataBlockDeltaEvent dbd && dbd.getDelta() != null) {
                    String text = dbd.getDelta();
                    if (active != null && !text.isEmpty()) active.dataBlocks.add(text);
                } else if (event instanceof ToolCallStartEvent tc && tc.getToolCallName() != null) {
                    String key = tc.getToolCallId() == null ? tc.getToolCallName() : tc.getToolCallId();
                    CallAcc acc = new CallAcc(tc.getToolCallName());
                    acc.stepNo = active == null ? 0 : active.stepNo;
                    pendingCalls.put(tc.getToolCallId(), acc);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", key); rec.put("name", tc.getToolCallName());
                    rec.put("args", ""); rec.put("result", "");
                    rec.put("startedAt", System.currentTimeMillis()); rec.put("error", false);
                    rec.put("done", false); rec.put("stepNo", acc.stepNo);
                    if (active != null) active.tools.add(rec);
                    stepTools.put(key, rec);
                    listener.onTool(tc.getToolCallName());
                    listener.onToolStart(key, tc.getToolCallName(), acc.stepNo);
                } else if (event instanceof ToolCallDeltaEvent td && pendingCalls.containsKey(td.getToolCallId())
                        && td.getDelta() != null) {
                    CallAcc acc = pendingCalls.get(td.getToolCallId());
                    acc.args.append(td.getDelta());
                    appendStepText(stepTools, td.getToolCallId(), acc.name, td.getDelta(), "args");
                    listener.onToolArgs(td.getToolCallId(), acc.name, td.getDelta());
                } else if (event instanceof ToolResultTextDeltaEvent trd) {
                    appendResult(pendingCalls, stepTools, trd.getToolCallId(), trd.getToolCallName(), trd.getDelta(), listener);
                } else if (event instanceof ToolResultDataDeltaEvent trd && trd.getData() != null) {
                    appendResult(pendingCalls, stepTools, trd.getToolCallId(), trd.getToolCallName(), blockText(trd.getData()), listener);
                } else if (event instanceof ToolProgressEvent tp && tp.getContent() != null) {
                    appendResult(pendingCalls, stepTools, tp.getToolCallId(), tp.getToolCallName(), blockText(tp.getContent()), true, listener);
                } else if (event instanceof ToolResultEndEvent te) {
                    CallAcc acc = findAcc(pendingCalls, te.getToolCallId(), te.getToolCallName());
                    if (acc != null) {
                        acc.endedAt = System.currentTimeMillis();
                        acc.error = te.getState() != null && te.getState().name().contains("ERROR");
                        if (acc.result.length() == 0 && acc.progress.length() > 0) {
                            acc.result.append(acc.progress);
                            appendStepText(stepTools, te.getToolCallId(), acc.name, acc.progress.toString(), "result");
                        }
                        if (acc.result.length() == 0) {
                            log.warn("Tool result captured empty after end: name={}, id={}, state={}",
                                    te.getToolCallName(), te.getToolCallId(), te.getState());
                        }
                        Map<String, Object> rec = stepTools.get(te.getToolCallId() == null ? acc.name : te.getToolCallId());
                        if (rec == null) rec = stepTools.get(acc.name);
                        if (rec != null) {
                            rec.put("durationMs", acc.endedAt - acc.startedAt);
                            rec.put("error", acc.error); rec.put("done", true);
                        }
                        listener.onToolEnd(te.getToolCallId() == null ? acc.name : te.getToolCallId(),
                                acc.name, acc.endedAt - acc.startedAt, acc.error);
                    }
                } else if (event instanceof ModelCallEndEvent mce) {
                    if (active != null) {
                        active.endedAt = System.currentTimeMillis();
                        active.usage = usageMap(mce.getUsage());
                        active.status = "complete";
                        listener.onStepEnd(active.stepNo, active.endedAt - active.startedAt, json(active.usage));
                    }
                } else if (event instanceof RequestStopEvent || event instanceof ExceedMaxItersEvent
                        || event instanceof AllToolsDeniedEvent || event instanceof AgentStartEvent
                        || event instanceof AgentEndEvent || event instanceof SubagentExposedEvent
                        || event instanceof CustomEvent || event instanceof HintBlockEvent
                        || event instanceof UserConfirmResultEvent || event instanceof ExternalExecutionResultEvent
                        || event instanceof RequireExternalExecutionEvent) {
                    if (active != null && event instanceof RequestStopEvent stop) {
                        // A permission-asking stop is an approval gate, not a user interrupt.
                        String reason = String.valueOf(stop.getReason());
                        if (reason.toLowerCase().contains("permission")) {
                            active.status = "awaiting-approval";
                        } else {
                            active.interrupted = true; active.status = "interrupted";
                        }
                    } else if (active != null && event instanceof ExceedMaxItersEvent) {
                        active.status = "max-iters";
                    } else if (active != null && event instanceof AllToolsDeniedEvent) {
                        active.status = "denied";
                    }
                    listener.onEvent(type, eventData(event));
                } else if (event instanceof RequireUserConfirmEvent confirm) {
                    pendingConfirm = confirm;
                    listener.onEvent(type, eventData(event));
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
                        acc.startedAt, dur, acc.error, acc.stepNo));
                if (acc.result.isEmpty()) {
                    log.warn("Tool result captured empty: name={}, args={}", acc.name, acc.args);
                }
                it.remove();
            }
            if (pendingConfirm == null) return finalResult;
            // Auto-approve every pending tool call, reusing the ORIGINAL ToolUseBlock.
            List<ConfirmResult> approvals = pendingConfirm.getToolCalls().stream()
                    .map(t -> new ConfirmResult(true, t)).toList();
            if (approvals.isEmpty()) return finalResult;
            current = UserMessage.builder()
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, approvals)).build();
        }
        throw new IllegalStateException("Too many PERMISSION_ASKING rounds (possible tool loop)");
    }

    /** Appends a streamed result chunk to the matching in-flight call, tolerating id/name mismatches. */
    private void appendResult(Map<String, CallAcc> pending, Map<String, Map<String, Object>> stepTools,
                              String id, String name, String text, ChatStreamListener listener) {
        appendResult(pending, stepTools, id, name, text, false, listener);
    }

    private void appendResult(Map<String, CallAcc> pending, Map<String, Map<String, Object>> stepTools,
                              String id, String name, String text, boolean asProgress,
                              ChatStreamListener listener) {
        if (text == null || text.isEmpty()) return;
        CallAcc acc = findAcc(pending, id, name);
        if (acc == null) return;
        if (asProgress) acc.progress.append(text); else acc.result.append(text);
        if (!asProgress) appendStepText(stepTools, id, name, text, "result");
        listener.onToolResult(id == null ? acc.name : id, acc.name, text);
    }

    /** Backfills args/result into the tool record that lives inside its owning step. */
    private static void appendStepText(Map<String, Map<String, Object>> stepTools, String id, String name,
                                       String delta, String field) {
        if (delta == null || delta.isEmpty()) return;
        Map<String, Object> rec = id == null ? null : stepTools.get(id);
        if (rec == null && name != null) rec = stepTools.get(name);
        if (rec == null) return;
        Object prev = rec.get(field);
        rec.put(field, (prev == null ? "" : String.valueOf(prev)) + delta);
    }

    /** Common event correlation fields across the 32 AgentEvent subclasses. */
    private static String replyIdOf(AgentEvent e) {
        if (e instanceof ModelCallStartEvent x) return x.getReplyId();
        if (e instanceof ModelCallEndEvent x) return x.getReplyId();
        if (e instanceof TextBlockDeltaEvent x) return x.getReplyId();
        if (e instanceof ThinkingBlockDeltaEvent x) return x.getReplyId();
        if (e instanceof DataBlockDeltaEvent x) return x.getReplyId();
        if (e instanceof ToolCallStartEvent x) return x.getReplyId();
        if (e instanceof ToolCallDeltaEvent x) return x.getReplyId();
        if (e instanceof ToolResultTextDeltaEvent x) return x.getReplyId();
        if (e instanceof ToolResultDataDeltaEvent x) return x.getReplyId();
        if (e instanceof ToolProgressEvent x) return x.getReplyId();
        if (e instanceof ToolResultEndEvent x) return x.getReplyId();
        return null;
    }

    private static String toolIdOf(AgentEvent e) {
        if (e instanceof ToolCallStartEvent x) return x.getToolCallId();
        if (e instanceof ToolCallDeltaEvent x) return x.getToolCallId();
        if (e instanceof ToolResultTextDeltaEvent x) return x.getToolCallId();
        if (e instanceof ToolResultDataDeltaEvent x) return x.getToolCallId();
        if (e instanceof ToolProgressEvent x) return x.getToolCallId();
        if (e instanceof ToolResultEndEvent x) return x.getToolCallId();
        if (e instanceof RequireUserConfirmEvent x && !x.getToolCalls().isEmpty())
            return x.getToolCalls().get(0).getId();
        return null;
    }

    private static String blockIdOf(AgentEvent e) {
        if (e instanceof TextBlockDeltaEvent x) return x.getBlockId();
        if (e instanceof ThinkingBlockDeltaEvent x) return x.getBlockId();
        if (e instanceof DataBlockDeltaEvent x) return x.getBlockId();
        return null;
    }

    /** Compact, human-readable payload for each event type — the raw evidence in events.jsonl. */
    private static String eventData(AgentEvent e) {
        if (e instanceof TextBlockDeltaEvent x) return x.getDelta() == null ? "" : x.getDelta();
        if (e instanceof ThinkingBlockDeltaEvent x) return x.getDelta() == null ? "" : x.getDelta();
        if (e instanceof DataBlockDeltaEvent x) return x.getDelta();
        if (e instanceof ToolCallDeltaEvent x) return x.getDelta() == null ? "" : x.getDelta();
        if (e instanceof ToolResultTextDeltaEvent x) return x.getDelta() == null ? "" : x.getDelta();
        if (e instanceof ToolResultDataDeltaEvent x) return blockText(x.getData());
        if (e instanceof ToolProgressEvent x) return blockText(x.getContent());
        if (e instanceof ToolCallStartEvent x) return x.getToolCallName() == null ? "" : x.getToolCallName();
        if (e instanceof ToolResultEndEvent x) return String.valueOf(x.getState());
        if (e instanceof ModelCallEndEvent x) return json(usageMap(x.getUsage()));
        if (e instanceof RequireUserConfirmEvent x) return x.getToolCalls().stream()
                .map(t -> t.getName() == null ? "" : t.getName()).collect(java.util.stream.Collectors.joining(", "));
        if (e instanceof AgentResultEvent x && x.getResult() != null) {
            String text = x.getResult().getTextContent();
            return text == null ? "" : (text.length() > 2000 ? text.substring(0, 2000) + "…" : text);
        }
        if (e instanceof RequestStopEvent x) return String.valueOf(x.getReason());
        if (e instanceof ExceedMaxItersEvent x) return x.getCurrentIter() + "/" + x.getMaxIters();
        if (e instanceof SubagentExposedEvent x) return x.getLabel() + " " + x.getSubagentId();
        if (e instanceof CustomEvent x) return x.getName() + " " + json(x.getValue());
        return "";
    }

    /** ChatUsage fields kept disjoint (DSH token semantics). */
    private static Map<String, Object> usageMap(ChatUsage u) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (u == null) return m;
        m.put("inputTokens", u.getInputTokens());
        m.put("outputTokens", u.getOutputTokens());
        m.put("cachedTokens", u.getCachedTokens());
        m.put("cacheCreationTokens", u.getCacheCreationTokens());
        m.put("reasoningTokens", u.getReasoningTokens());
        m.put("toolUsePromptTokens", u.getToolUsePromptTokens());
        m.put("totalTokens", u.getTotalTokens());
        m.put("time", u.getTime());
        return m;
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

    /**
     * 1:1 trajectory: rows are projected from per-LLM-call steps + independent tool records +
     * the raw event log; legacy sessions (no step data) still project from chat_message.
     */
    public Map<String, Object> trajectory(String sessionId) {
        List<Message> msgs = messages(sessionId);
        List<Map<String, Object>> steps = steps(sessionId);
        List<Map<String, Object>> events = events(sessionId);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        int turns = 0, toolCalls = 0;
        long totalMs = 0;

        for (Message m : msgs) {
            if ("user".equals(m.role())) {
                turns++;
                rows.add(row("user", turns, 0, 0, m.content() == null ? "" : m.content(),
                        "", "", m.createdAt() == null ? "" : m.createdAt(), null, null, null));
            }
        }

        if (!steps.isEmpty()) {
            for (Map<String, Object> s : steps) {
                int turn = s.get("turnNo") == null ? 0 : ((Number) s.get("turnNo")).intValue();
                int stepNo = s.get("stepNo") == null ? 0 : ((Number) s.get("stepNo")).intValue();
                String content = String.valueOf(s.getOrDefault("content", ""));
                String thinking = String.valueOf(s.getOrDefault("thinking", ""));
                String status = String.valueOf(s.getOrDefault("status", "complete"));
                Number dur = (Number) s.get("durationMs");
                totalMs += dur == null ? 0 : dur.longValue();
                // 1:1 ledger: each model call contributes its own THINKING row
                // and its own REPLY row. Never merge thinking into the reply row —
                // the trajectory is the audit trail used to replay what happened.
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("usage", s.get("usage"));
                extra.put("startedAt", s.get("startedAt"));
                extra.put("firstTokenAt", s.get("firstTokenAt"));
                extra.put("endedAt", s.get("endedAt"));
                extra.put("interrupted", s.get("interrupted"));
                extra.put("stepNo", stepNo);
                if (!thinking.isEmpty()) {
                    Map<String, Object> thinkRow = row("thinking", turn, stepNo, stepNo * 10,
                            preview(thinking, 240), thinking, "", "", null,
                            dur == null ? null : dur.longValue(), status);
                    thinkRow.put("meta", new LinkedHashMap<>(extra));
                    rows.add(thinkRow);
                }

                List<?> tools = parseList(mapper, String.valueOf(s.getOrDefault("toolCalls", "")));
                for (Object o : tools) {
                    if (!(o instanceof Map<?, ?> tm)) continue;
                    toolCalls++;
                    String name = String.valueOf(tm.get("name") == null ? "" : tm.get("name"));
                    String args = String.valueOf(tm.get("args") == null ? "" : tm.get("args"));
                    String result = String.valueOf(tm.get("result") == null ? "" : tm.get("result"));
                    Number td = tm.get("durationMs") instanceof Number n ? n : null;
                    Map<String, Object> toolRow = row("tool", turn, stepNo, stepNo * 10 + 1,
                            name, args, "", "", tm.get("startedAt") == null ? null : String.valueOf(tm.get("startedAt")),
                            td == null ? null : td.longValue(),
                            Boolean.TRUE.equals(tm.get("error")) ? "failed" : (Boolean.TRUE.equals(tm.get("done")) ? "complete" : "running"));
                    toolRow.put("result", result);
                    rows.add(toolRow);
                }
                if (!content.isEmpty() || (thinking.isEmpty() && tools.isEmpty())) {
                    Map<String, Object> msgRow = row("message", turn, stepNo, stepNo * 10 + 2,
                            content.isEmpty() ? "（仅工具调用）" : preview(content, 240),
                            content, "", "", null, dur == null ? null : dur.longValue(), status);
                    msgRow.put("meta", extra);
                    rows.add(msgRow);
                }
            }
        } else {
            // Legacy data produced before step capture existed.
            int turn = 0;
            for (Message m : msgs) {
                if ("user".equals(m.role())) continue;
                String content = m.content() == null ? "" : m.content();
                rows.add(row("message", turn, 0, 10, preview(content, 240), content, "",
                        m.createdAt() == null ? "" : m.createdAt(), null, null, null));
                try {
                    var root = mapper.readTree(m.toolCalls() == null ? "" : m.toolCalls());
                    var arr = root.has("tools") ? root.get("tools") : null;
                    if (arr != null && arr.isArray()) {
                        toolCalls += arr.size();
                        for (var node : arr) {
                            Map<String, Object> toolRow = row("tool", turn, 0, 11,
                                    node.path("name").asText(), node.path("args").asText(), "", "",
                                    node.hasNonNull("startedAt") ? node.get("startedAt").asText() : null,
                                    node.hasNonNull("durationMs") ? node.get("durationMs").asLong() : null,
                                    node.path("error").asBoolean() ? "failed" : "complete");
                            toolRow.put("result", node.path("result").asText());
                            rows.add(toolRow);
                        }
                    }
                    if (root.hasNonNull("durationMs")) totalMs += root.get("durationMs").asLong();
                } catch (Exception ignored) { /* legacy comma list: no structured tools */ }
            }
        }

        // Runtime events that are not assistant/tool content still belong in the audit trail.
        // Place each one next to the model call that was active when it happened (event seq order).
        List<int[]> callStarts = new ArrayList<>(); // {event seq, stepNo, turnNo}
        int si = 0;
        for (Map<String, Object> e : events) {
            if (!"MODEL_CALL_START".equals(e.get("eventType")) || si >= steps.size()) continue;
            int seq = Integer.parseInt(String.valueOf(e.get("seq")));
            callStarts.add(new int[]{seq,
                    ((Number) steps.get(si).getOrDefault("stepNo", 0)).intValue(),
                    ((Number) steps.get(si).getOrDefault("turnNo", 0)).intValue()});
            si++;
        }
        Set<String> contextTypes = Set.of("REQUEST_STOP", "EXCEED_MAX_ITERS", "ALL_TOOLS_DENIED",
                "SUBAGENT_EXPOSED", "CUSTOM", "HINT_BLOCK", "USER_CONFIRM_RESULT",
                "EXTERNAL_EXECUTION_RESULT", "REQUIRE_EXTERNAL_EXECUTION");
        for (Map<String, Object> e : events) {
            String type = String.valueOf(e.getOrDefault("eventType", ""));
            if (!contextTypes.contains(type)) continue;
            int seq = Integer.parseInt(String.valueOf(e.get("seq")));
            int cTurn = turns, cStep = 0, cOrder = 9990;
            for (int[] cs : callStarts) {
                if (cs[0] <= seq) { cTurn = cs[2]; cStep = cs[1]; cOrder = cs[1] * 10 + 5; }
            }
            String data = String.valueOf(e.getOrDefault("data", ""));
            rows.add(row("context", cTurn, cStep, cOrder,
                    type + (data.isEmpty() ? "" : " · " + preview(data, 120)),
                    data, "", String.valueOf(e.getOrDefault("createdAt", "")), null, null, "complete"));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("seq", e.get("seq")); meta.put("eventType", type);
            // Harness bookkeeping events (tool observation / context build) are
            // retained in the raw log but hidden from the main ledger by default.
            String dataText = data == null ? "" : data;
            meta.put("internal", "CUSTOM".equals(type)
                    && (dataText.startsWith("action_observation") || dataText.startsWith("context_build")));
            rows.get(rows.size() - 1).put("meta", meta);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> (Integer) r.get("turn"))
                .thenComparingInt(r -> (Integer) r.get("order")));

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
        result.put("steps", steps);
        result.put("events", events);
        result.put("rows", rows);
        result.put("createdAt", s.createdAt());
        return result;
    }

    private static String preview(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static List<?> parseList(ObjectMapper mapper, String json) {
        try {
            var node = mapper.readTree(json == null ? "" : json);
            if (node != null && node.isArray()) {
                List<Object> list = new ArrayList<>();
                node.forEach(n -> list.add(mapper.convertValue(n, Map.class)));
                return list;
            }
        } catch (Exception ignored) { /* not JSON */ }
        return List.of();
    }

    private static Map<String, Object> row(String kind, int turn, int stepNo, int order,
                                           String text, String detail, String thinking, String createdAt,
                                           String startedAt, Long durationMs, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind); r.put("turn", turn); r.put("stepNo", stepNo); r.put("order", order);
        r.put("text", text); r.put("detail", detail); r.put("thinking", thinking);
        r.put("createdAt", createdAt); r.put("startedAt", startedAt);
        r.put("durationMs", durationMs); r.put("status", status);
        return r;
    }
}
