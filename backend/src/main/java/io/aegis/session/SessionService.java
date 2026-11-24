package io.aegis.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
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
    /** Result of one chat turn: assistant text plus names of tools that actually executed. */
    public record ChatResult(String reply, List<String> toolCalls) {}

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
        List<String> tools = new ArrayList<>();
        Msg result = runWithAutoApprove(agent, current, ctx, tools);
        String reply = result == null ? "" : result.getTextContent();
        return new ChatResult(reply, tools);
    }

    /**
     * Drives a synchronous conversation via the event stream, auto-approving tool calls that land
     * in PERMISSION_ASKING. Mirrors agentscope's PlanModeAutoExample. Event stream (not blocking
     * call()) is used so tool invocation names (ToolCallStartEvent) can be captured for the
     * trajectory view. Fits Aegis' three tiers: EXPLORE denies writes at engine level (no ASKING),
     * while ACCEPT_EDITS / BYPASS auto-approve ASKING so writes proceed.
     */
    private Msg runWithAutoApprove(HarnessAgent agent, Msg userMsg, RuntimeContext ctx, List<String> executedTools) {
        Msg current = userMsg;
        final int maxRounds = 12;
        for (int round = 0; round < maxRounds; round++) {
            final java.util.concurrent.atomic.AtomicReference<Msg> finalResult =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            final java.util.concurrent.atomic.AtomicReference<RequireUserConfirmEvent> pendingConfirm =
                    new java.util.concurrent.atomic.AtomicReference<>(null);

            Flux<AgentEvent> stream = agent.streamEvents(current, ctx);
            stream.doOnNext(event -> {
                if (event instanceof AgentResultEvent are && are.getResult() != null) {
                    finalResult.set(are.getResult());
                } else if (event instanceof ToolCallStartEvent tc && tc.getToolCallName() != null) {
                    executedTools.add(tc.getToolCallName());
                } else if (event instanceof RequireUserConfirmEvent confirm) {
                    pendingConfirm.set(confirm);
                }
            }).blockLast();

            RequireUserConfirmEvent confirm = pendingConfirm.get();
            if (confirm == null) {
                return finalResult.get();
            }
            // Auto-approve every pending tool call, reusing the ORIGINAL ToolUseBlock.
            List<ConfirmResult> approvals = confirm.getToolCalls().stream()
                    .map(t -> new ConfirmResult(true, t))
                    .toList();
            if (approvals.isEmpty()) {
                return finalResult.get();
            }
            current = UserMessage.builder()
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, approvals))
                    .build();
        }
        throw new IllegalStateException("Too many PERMISSION_ASKING rounds (possible tool loop)");
    }

    /** Trajectory summary derived from stored messages. */
    public Map<String, Object> trajectory(String sessionId) {
        List<Message> msgs = messages(sessionId);
        int turns = 0, toolCalls = 0;
        for (Message m : msgs) {
            if ("user".equals(m.role())) turns++;
            if (m.toolCalls() != null && !m.toolCalls().isBlank()) {
                toolCalls += m.toolCalls().split(",").length;
            }
        }
        Session s = jdbc.query("SELECT * FROM chat_session WHERE id=?", SESSION_MAP, sessionId).get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("turns", turns);
        result.put("toolCalls", toolCalls);
        result.put("messages", msgs.stream().map(m -> Map.<String,Object>of(
                "role", m.role(), "content", m.content(), "toolCalls", m.toolCalls() == null ? "" : m.toolCalls(),
                "createdAt", m.createdAt())).toList());
        result.put("createdAt", s.createdAt());
        return result;
    }
}
