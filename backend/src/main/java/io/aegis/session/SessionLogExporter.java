package io.aegis.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a session log archive (DSH session-log-export equivalent). The zip contains:
 * - session.jsonl: the full conversation as canonical JSON lines (one per message,
 *   tool_calls embedded as a JSON object so the file parses line-by-line);
 * - trajectory.json: the DSH-style trajectory payload the /trajectory endpoint returns;
 * - meta.json: session metadata (id, agent, permission mode, timestamps, title).
 */
@Service
public class SessionLogExporter {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;

    public SessionLogExporter(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    /** Writes the archive for one session to the given stream (caller owns closing). */
    public void export(String sessionId, ZipOutputStream zip) throws IOException {
        SessionService.Session s = jdbc.query("SELECT * FROM chat_session WHERE id=?",
                (rs, n) -> new SessionService.Session(rs.getString("id"), rs.getString("agent_id"),
                        rs.getLong("user_id"), rs.getString("title"), rs.getString("permission_mode"),
                        rs.getString("created_at"), rs.getString("updated_at")),
                sessionId).get(0);
        List<SessionService.Message> msgs = sessions.messages(sessionId);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // meta.json
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", s.id());
        meta.put("agentId", s.agentId());
        meta.put("title", s.title());
        meta.put("permissionMode", s.permissionMode());
        meta.put("createdAt", s.createdAt());
        meta.put("updatedAt", s.updatedAt());
        meta.put("exportedAt", java.time.OffsetDateTime.now().toString());
        zip.putNextEntry(new ZipEntry("meta.json"));
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
        zip.closeEntry();

        // session.jsonl — one JSON line per message (canonical, line-parseable).
        zip.putNextEntry(new ZipEntry("session.jsonl"));
        for (SessionService.Message m : msgs) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", m.role());
            line.put("content", m.content() == null ? "" : m.content());
            if (m.toolCalls() != null && !m.toolCalls().isBlank()) {
                try {
                    line.put("toolCalls", mapper.readTree(m.toolCalls()));
                } catch (Exception e) {
                    line.put("toolCallsRaw", m.toolCalls());
                }
            }
            line.put("createdAt", m.createdAt() == null ? "" : m.createdAt());
            zip.write((mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();

        // steps.json — one record per LLM invocation (thinking/text/tool calls/usage).
        zip.putNextEntry(new ZipEntry("steps.json"));
        zip.write(mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(sessions.steps(sessionId)));
        zip.closeEntry();

        // events.jsonl — verbatim runtime event log (the 1:1 audit trail).
        zip.putNextEntry(new ZipEntry("events.jsonl"));
        for (Map<String, Object> e : sessions.events(sessionId)) {
            zip.write((mapper.writeValueAsString(e) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        zip.closeEntry();

        // trajectory.json — the same payload the trajectory view renders.
        zip.putNextEntry(new ZipEntry("trajectory.json"));
        zip.write(mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(sessions.trajectory(sessionId)));
        zip.closeEntry();
    }

    /** Archive filename for one session (mirrors DSH's dsh-session-<id>.zip naming). */
    public static String zipFilename(String sessionId) {
        return "aegis-session-" + sessionId.replaceAll("[^A-Za-z0-9_-]", "_") + ".zip";
    }
}
