package io.aegis.session;

import io.aegis.agent.AgentRepository;
import io.aegis.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/** Session CRUD + chat + trajectory. Ownership: sessions belong to creator. */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final AgentRepository agents;
    private final TitleService titles;
    private final SessionLogExporter exporter;

    public SessionController(SessionService sessions, AgentRepository agents,
                             TitleService titles, SessionLogExporter exporter) {
        this.sessions = sessions;
        this.agents = agents;
        this.titles = titles;
        this.exporter = exporter;
    }

    private Long uid() { return AuthContext.get().id; }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(sessions.listForUser(uid()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return sessions.get(id, uid())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String agentId = body.get("agentId");
        if (agentId == null || agents.findById(agentId).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown agent"));
        }
        String id = UUID.randomUUID().toString();
        SessionService.Session s = sessions.create(id, agentId, uid(),
                body.getOrDefault("title", "New chat"),
                body.getOrDefault("permissionMode", "ACCEPT_EDITS"));
        return ResponseEntity.ok(s);
    }

    @PutMapping("/{id}/permission")
    public ResponseEntity<?> setPermission(@PathVariable String id, @RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode == null || !Set.of("EXPLORE", "ACCEPT_EDITS", "BYPASS").contains(mode)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid mode"));
        }
        int n = sessions.setPermissionMode(id, uid(), mode);
        return n > 0 ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id) {
        sessions.get(id, uid()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(sessions.messages(id));
    }

    @PostMapping("/{id}/chat")
    public ResponseEntity<?> chat(@PathVariable String id, @RequestBody Map<String, String> body) {
        SessionService.Session s = sessions.get(id, uid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String text = body.get("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message required"));
        }
        boolean firstTurn = sessions.messages(id).stream().noneMatch(m -> "user".equals(m.role()));
        sessions.saveMessage(id, "user", text, null);
        SessionService.ChatResult result;
        try {
            result = sessions.chat(s, uid(), text);
        } catch (Exception e) {
            String reply = "[Error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            sessions.saveMessage(id, "assistant", reply, null);
            return ResponseEntity.ok(Map.of("reply", reply, "error", true));
        }
        sessions.saveMessage(id, "assistant", result.reply(), result.toolCallsJson());
        if (firstTurn) titles.generateFor(id);
        return ResponseEntity.ok(Map.of("reply", result.reply(), "toolCalls", result.toolCalls()));
    }

    /**
     * Streaming chat (SSE). Events: delta (assistant text chunk), thinking (reasoning chunk),
     * tool (tool invocation started), done (final reply + toolCalls), error. Written synchronously
     * on the servlet thread, which matches SessionService's blocking event iteration.
     */
    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chatStream(@PathVariable String id, @RequestBody Map<String, String> body,
                           HttpServletResponse response) throws IOException {
        SessionService.Session s = sessions.get(id, uid())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String text = body.get("message");
        if (text == null || text.isBlank()) {
            response.sendError(400, "message required");
            return;
        }
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = response.getWriter();
        ObjectMapper mapper = new ObjectMapper();
        boolean firstTurn = sessions.messages(id).stream().noneMatch(m -> "user".equals(m.role()));
        sessions.saveMessage(id, "user", text, null);
        try {
            SessionService.ChatResult result = sessions.chat(s, uid(), text, new SessionService.ChatStreamListener() {
                @Override public void onDelta(String d) {
                    try { sendEvent(writer, mapper, "delta", Map.of("text", d)); } catch (IOException ignored) { }
                }
                @Override public void onThinkingDelta(String d) {
                    try { sendEvent(writer, mapper, "thinking", Map.of("text", d)); } catch (IOException ignored) { }
                }
                @Override public void onTool(String tool) {
                    try { sendEvent(writer, mapper, "tool", Map.of("name", tool)); } catch (IOException ignored) { }
                }
                @Override public void onToolStart(String id, String name) {
                    try { sendEvent(writer, mapper, "tool_start", Map.of("id", id, "name", name)); } catch (IOException ignored) { }
                }
                @Override public void onToolArgs(String id, String name, String delta) {
                    try { sendEvent(writer, mapper, "tool_args", Map.of("id", id, "delta", delta)); } catch (IOException ignored) { }
                }
                @Override public void onToolResult(String id, String name, String delta) {
                    try { sendEvent(writer, mapper, "tool_result", Map.of("id", id, "delta", delta)); } catch (IOException ignored) { }
                }
                @Override public void onToolEnd(String id, String name, long durationMs, boolean error) {
                    try { sendEvent(writer, mapper, "tool_end",
                            Map.of("id", id, "durationMs", durationMs, "error", error)); } catch (IOException ignored) { }
                }
            });
            sessions.saveMessage(id, "assistant", result.reply(), result.toolCallsJson());
            if (firstTurn) titles.generateFor(id);
            // Re-read so the freshly generated title reaches the client in done.
            String title = sessions.get(id, uid()).map(SessionService.Session::title).orElse(null);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("reply", result.reply());
            done.put("toolCalls", result.toolCalls());
            done.put("toolCallsJson", result.toolCallsJson() == null ? "" : result.toolCallsJson());
            done.put("title", title == null ? "" : title);
            sendEvent(writer, mapper, "done", done);
        } catch (Exception e) {
            String err = "[Error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            sessions.saveMessage(id, "assistant", err, null);
            sendEvent(writer, mapper, "error", Map.of("message", err));
        } finally {
            writer.flush();
        }
    }

    private static void sendEvent(PrintWriter writer, ObjectMapper mapper, String name, Object data)
            throws IOException {
        writer.write("event: " + name + "\n");
        writer.write("data: " + mapper.writeValueAsString(data) + "\n\n");
        writer.flush();
    }

    @GetMapping("/{id}/trajectory")
    public ResponseEntity<?> trajectory(@PathVariable String id) {
        sessions.get(id, uid()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(sessions.trajectory(id));
    }

    /**
     * Downloads the session log archive (DSH-style export): meta.json + session.jsonl +
     * trajectory.json inside one zip. Content-Disposition forces the browser save dialog.
     */
    @GetMapping("/{id}/export")
    public void export(@PathVariable String id, HttpServletResponse response) throws IOException {
        sessions.get(id, uid()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + SessionLogExporter.zipFilename(id) + "\"");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            exporter.export(id, zip);
        }
    }
}
