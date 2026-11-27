package io.aegis.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegis.agent.Agent;
import io.aegis.agent.AgentRepository;
import io.aegis.auth.AuthContext;
import io.aegis.session.AgentRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tool management for agents the current user can manage (Harness → Tool tab).
 *
 * <p>The authoritative config is {@code workspace/tools.json}, which HarnessAgent loads
 * natively on build: {@code allow}/{@code deny} filter built-in tools and {@code mcpServers}
 * declare MCP connections. We expose a typed view over that file:
 * <ul>
 *   <li>built-in tools are read from the framework's real toolkit (name/description)
 *       with per-tool enabled state derived from allow/deny;
 *   <li>MCP servers are stored verbatim in {@code mcpServers} (transport/url/command/...).
 * </ul>
 *
 * <p>Changes apply to the next session build; the cached agent is invalidated on write.
 * No external MCP marketplace is bundled — suitable for offline/intranet deployment.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class ToolController {

    private final AgentRepository agents;
    private final BuiltinTools builtins;
    private final AgentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolController(AgentRepository agents, BuiltinTools builtins, AgentRegistry registry) {
        this.agents = agents;
        this.builtins = builtins;
        this.registry = registry;
    }

    private Agent requireManageable(String agentId) {
        Agent a = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AuthContext.CurrentUser c = AuthContext.get();
        if (!c.isSuperAdmin() && !Objects.equals(a.ownerId, c.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return a;
    }

    private Path configPath(String agentId) {
        String root = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        return Path.of(root, agentId, "tools.json").normalize().toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfig(String agentId) {
        Path p = configPath(agentId);
        if (!Files.exists(p)) return new LinkedHashMap<>();
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw.isBlank()) return new LinkedHashMap<>();
            Object v = mapper.readValue(raw, Object.class);
            return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "tools.json unreadable: " + e.getMessage());
        }
    }

    private void writeConfig(String agentId, Map<String, Object> cfg) {
        Path p = configPath(agentId);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "tools.json write failed: " + e.getMessage());
        }
        registry.invalidate(agentId);
    }

    /** GET /tools/config — catalog + current config in one shot. */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> view(@PathVariable String agentId) {
        requireManageable(agentId);
        Map<String, Object> cfg = readConfig(agentId);
        Set<String> deny = toStringSet(cfg.get("deny"));
        Set<String> allow = toStringSet(cfg.get("allow"));
        boolean allowMode = allow != null && !allow.isEmpty();

        List<Map<String, Object>> builtinRows = new ArrayList<>();
        for (BuiltinTools.ToolInfo t : builtins.all()) {
            boolean enabled = !deny.contains(t.name()) && (!allowMode || allow.contains(t.name()));
            builtinRows.add(Map.of(
                    "name", t.name(), "category", t.category(),
                    "readOnly", t.readOnly(), "description", t.description(),
                    "builtin", true, "enabled", enabled));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("builtins", builtinRows);
        body.put("allow", cfg.getOrDefault("allow", List.of()));
        body.put("deny", cfg.getOrDefault("deny", List.of()));
        body.put("mcpServers", cfg.getOrDefault("mcpServers", Map.of()));
        return ResponseEntity.ok(body);
    }

    /** PUT /tools/config — overwrite allow/deny/mcpServers. */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable String agentId, @RequestBody Map<String, Object> body) {
        requireManageable(agentId);
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");

        Object allowObj = body.get("allow");
        Object denyObj = body.get("deny");
        Object mcpObj = body.get("mcpServers");
        List<String> allow = toStringList(allowObj, "allow");
        List<String> deny = toStringList(denyObj, "deny");
        Map<String, Map<String, Object>> mcp = toServerMap(mcpObj);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("// description", "Aegis tool config: allow/deny built-ins + MCP servers");
        cfg.put("allow", allow);
        cfg.put("deny", deny);
        cfg.put("mcpServers", mcp);
        writeConfig(agentId, cfg);
        return view(agentId);
    }

    private static Set<String> toStringSet(Object v) {
        List<String> l = toStringList(v, null);
        return l == null ? Set.of() : new HashSet<>(l);
    }

    private static List<String> toStringList(Object v, String field) {
        if (v == null) return field == null ? null : List.of();
        if (!(v instanceof List<?> list)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an array");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " contains a blank/non-string entry");
            }
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> toServerMap(Object v) {
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> raw)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mcpServers must be an object");
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String name = String.valueOf(e.getKey());
            if (name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "blank MCP server name");
            if (!(e.getValue() instanceof Map<?, ?> sv)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mcpServers." + name + " must be an object");
            }
            Map<String, Object> s = new LinkedHashMap<>();
            for (Map.Entry<?, ?> se : sv.entrySet()) s.put(String.valueOf(se.getKey()), se.getValue());
            String transport = String.valueOf(s.getOrDefault("transport", "")).toLowerCase(Locale.ROOT);
            switch (transport) {
                case "stdio" -> {
                    if (s.get("command") == null || String.valueOf(s.get("command")).isBlank())
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + ": stdio requires command");
                }
                case "sse", "http", "streamable-http", "streamablehttp" -> {
                    if (s.get("url") == null || String.valueOf(s.get("url")).isBlank())
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + ": " + transport + " requires url");
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        name + ": unsupported transport '" + transport + "'");
            }
            out.put(name, s);
        }
        return out;
    }
}
