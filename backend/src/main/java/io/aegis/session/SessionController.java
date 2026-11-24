package io.aegis.session;

import io.aegis.agent.AgentRepository;
import io.aegis.auth.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Session CRUD + chat + trajectory. Ownership: sessions belong to creator. */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final AgentRepository agents;

    public SessionController(SessionService sessions, AgentRepository agents) {
        this.sessions = sessions;
        this.agents = agents;
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
        sessions.saveMessage(id, "user", text, null);
        SessionService.ChatResult result;
        try {
            result = sessions.chat(s, uid(), text);
        } catch (Exception e) {
            String reply = "[Error] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            sessions.saveMessage(id, "assistant", reply, null);
            return ResponseEntity.ok(Map.of("reply", reply, "error", true));
        }
        String toolCalls = result.toolCalls().isEmpty() ? null : String.join(", ", result.toolCalls());
        sessions.saveMessage(id, "assistant", result.reply(), toolCalls);
        return ResponseEntity.ok(Map.of("reply", result.reply(), "toolCalls", result.toolCalls()));
    }

    @GetMapping("/{id}/trajectory")
    public ResponseEntity<?> trajectory(@PathVariable String id) {
        sessions.get(id, uid()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(sessions.trajectory(id));
    }
}
