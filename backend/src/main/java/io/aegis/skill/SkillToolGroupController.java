package io.aegis.skill;

import io.aegis.agent.Agent;
import io.aegis.agent.AgentRepository;
import io.aegis.auth.AuthContext;
import io.aegis.session.AgentRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * SkillToolGroup management (Harness → Tool → Skill Tool Groups).
 *
 * <p>The group is a bundle of tools activated by the framework when a given skill
 * is loaded. Definitions live in SQLite and are replayed into each agent's Toolkit
 * at build time. Activate-on-skill is validated against the agent's actual skills
 * so a typo cannot create a group that never activates.</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tool-groups")
public class SkillToolGroupController {

    private final AgentRepository agents;
    private final SkillToolGroupStore store;
    private final SkillStore skills;
    private final AgentRegistry registry;

    public SkillToolGroupController(AgentRepository agents, SkillToolGroupStore store,
                                    SkillStore skills, AgentRegistry registry) {
        this.agents = agents;
        this.store = store;
        this.skills = skills;
        this.registry = registry;
    }

    private void requireManageable(String agentId) {
        Agent a = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AuthContext.CurrentUser c = AuthContext.get();
        if (!c.isSuperAdmin() && !Objects.equals(a.ownerId, c.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void requireSkillExists(String agentId, String skillName) {
        if (skills.get(agentId, skillName) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "激活技能不存在: " + skillName);
        }
    }

    private static Map<String, Object> map(SkillToolGroupStore.Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("description", g.description());
        m.put("activateOnSkill", g.activateOnSkill());
        m.put("tools", g.tools());
        m.put("createdAt", g.createdAt());
        m.put("updatedAt", g.updatedAt());
        return m;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String agentId) {
        requireManageable(agentId);
        return ResponseEntity.ok(store.list(agentId).stream().map(SkillToolGroupController::map).toList());
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> get(@PathVariable String agentId, @PathVariable String name) {
        requireManageable(agentId);
        SkillToolGroupStore.Group g = store.get(agentId, name);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在");
        return ResponseEntity.ok(map(g));
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        requireManageable(agentId);
        String name = str(body.get("name")).trim();
        String description = str(body.get("description")).trim();
        String activateOnSkill = str(body.get("activateOnSkill")).trim();
        List<String> tools = strings(body.get("tools"));
        requireSkillExists(agentId, activateOnSkill);
        if (!store.create(agentId, name, description, activateOnSkill, tools)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分组已存在: " + name);
        }
        registry.invalidate(agentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                map(store.get(agentId, name)));
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String agentId, @PathVariable String name,
                                    @RequestBody Map<String, Object> body) {
        requireManageable(agentId);
        String description = str(body.get("description")).trim();
        String activateOnSkill = str(body.get("activateOnSkill")).trim();
        List<String> tools = strings(body.get("tools"));
        requireSkillExists(agentId, activateOnSkill);
        if (!store.update(agentId, name, description, activateOnSkill, tools)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在");
        }
        registry.invalidate(agentId);
        return ResponseEntity.ok(map(store.get(agentId, name)));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String agentId, @PathVariable String name) {
        requireManageable(agentId);
        if (!store.delete(agentId, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在");
        }
        registry.invalidate(agentId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o).trim());
        return out;
    }
}
