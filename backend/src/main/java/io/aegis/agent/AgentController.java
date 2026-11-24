package io.aegis.agent;

import io.aegis.auth.AuthContext;
import io.aegis.tool.BuiltinTools;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Agent CRUD with owner-based authorization + tool catalog/config. */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRepository agents;
    private final BuiltinTools tools;

    public AgentController(AgentRepository agents, BuiltinTools tools) {
        this.agents = agents;
        this.tools = tools;
    }

    private AuthContext.CurrentUser cur() { return AuthContext.get(); }

    private boolean canManage(Agent a) {
        AuthContext.CurrentUser c = cur();
        return c.isSuperAdmin() || Objects.equals(a.ownerId, c.id);
    }

    private Map<String, Object> toDto(Agent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("name", a.name);
        m.put("description", a.description == null ? "" : a.description);
        m.put("ownerId", a.ownerId);
        m.put("builtin", a.builtin);
        m.put("sysPrompt", a.sysPrompt == null ? "" : a.sysPrompt);
        m.put("model", a.model == null ? "" : a.model);
        m.put("createdAt", a.createdAt == null ? "" : a.createdAt);
        return m;
    }

    /** Agents visible to current user (all for super admin; builtin + own otherwise). */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "scope", defaultValue = "visible") String scope) {
        AuthContext.CurrentUser c = cur();
        List<Agent> list = "manageable".equals(scope)
                ? agents.manageableBy(c.id, c.isSuperAdmin())
                : agents.visibleTo(c.id, c.isSuperAdmin());
        return ResponseEntity.ok(list.stream().map(this::toDto).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Agent a = agents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!a.builtin && !canManage(a) && !agents.visibleTo(cur().id, cur().isSuperAdmin()).stream()
                .anyMatch(x -> x.id.equals(id))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok(toDto(a));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        AuthContext.CurrentUser c = cur();
        if (!(c.isSuperAdmin() || c.isAgentAdmin())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent admin only");
        }
        String id = body.get("id");
        String name = body.get("name");
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and name required"));
        }
        if (agents.findById(id).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agent id exists"));
        }
        agents.create(id, name, body.getOrDefault("description", ""), c.id, false,
                body.getOrDefault("sysPrompt", "You are a helpful assistant."),
                body.getOrDefault("toolsJson", AgentRepository.DEFAULT_TOOLS_JSON),
                body.get("model"));
        ensureWorkspace(id, body.getOrDefault("sysPrompt", "You are a helpful assistant."));
        return ResponseEntity.ok(toDto(agents.findById(id).orElseThrow()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        Agent a = agents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!canManage(a)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        agents.update(id,
                body.getOrDefault("name", a.name),
                body.getOrDefault("description", a.description == null ? "" : a.description),
                body.getOrDefault("sysPrompt", a.sysPrompt == null ? "" : a.sysPrompt),
                body.getOrDefault("toolsJson", a.toolsJson == null ? AgentRepository.DEFAULT_TOOLS_JSON : a.toolsJson),
                body.containsKey("model") ? body.get("model") : a.model);
        return ResponseEntity.ok(toDto(agents.findById(id).orElseThrow()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        Agent a = agents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (a.builtin) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot delete builtin agent");
        if (!canManage(a)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        agents.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Built-in tool catalog for the tool picker. */
    @GetMapping("/tools/catalog")
    public ResponseEntity<?> toolCatalog() {
        return ResponseEntity.ok(tools.catalog());
    }

    /** Effective tool config for an agent (allow/deny + builtin flag per tool). */
    @GetMapping("/{id}/tools")
    public ResponseEntity<?> getTools(@PathVariable String id) {
        Agent a = agents.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(Map.of("toolsJson", a.toolsJson == null ? AgentRepository.DEFAULT_TOOLS_JSON : a.toolsJson));
    }

    private void ensureWorkspace(String id, String sysPrompt) {
        try {
            String root = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
            java.nio.file.Path ws = java.nio.file.Path.of(root, id);
            java.nio.file.Files.createDirectories(ws.resolve("skills"));
            java.nio.file.Files.createDirectories(ws.resolve("subagents"));
            java.nio.file.Files.createDirectories(ws.resolve("memory"));
            java.nio.file.Files.writeString(ws.resolve("AGENTS.md"), "# " + id + "\n\n" + sysPrompt + "\n");
            java.nio.file.Files.writeString(ws.resolve("tools.json"), AgentRepository.DEFAULT_TOOLS_JSON);
        } catch (Exception e) {
            throw new RuntimeException("failed to create workspace for agent " + id, e);
        }
    }
}
