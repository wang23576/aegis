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
 * Skill management for agents the current user can manage (Harness → Skill tab).
 *
 * <p>The active skill is {@code workspace/skills/<name>/SKILL.md} (framework-native,
 * visible in the Workspace tab); this controller adds CRUD + a revision history
 * backed by SQLite. Framework agent cache is invalidated after every write so the
 * next session picks up the change.</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class SkillController {

    private final AgentRepository agents;
    private final SkillStore store;
    private final AgentRegistry registry;

    public SkillController(AgentRepository agents, SkillStore store, AgentRegistry registry) {
        this.agents = agents;
        this.store = store;
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

    private static Map<String, Object> skillMap(SkillStore.Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("content", s.content());
        m.put("source", s.source());
        return m;
    }

    private static Map<String, Object> versionMap(SkillStore.Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.version());
        m.put("description", v.description());
        m.put("content", v.content());
        m.put("savedAt", v.savedAt());
        m.put("note", v.note());
        return m;
    }

    /** GET /skills — list all skills with their version count. */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable String agentId) {
        requireManageable(agentId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillStore.Skill s : store.list(agentId)) {
            Map<String, Object> m = skillMap(s);
            Integer count = store.versions(agentId, s.name()).size();
            m.put("versionCount", count);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** GET /skills/{name} — skill detail. */
    @GetMapping("/{name}")
    public ResponseEntity<?> get(@PathVariable String agentId, @PathVariable String name) {
        requireManageable(agentId);
        SkillStore.Skill s = store.get(agentId, name);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill 不存在");
        return ResponseEntity.ok(skillMap(s));
    }

    /** POST /skills — create a new skill (fails if it exists). */
    @PostMapping
    public ResponseEntity<?> create(@PathVariable String agentId, @RequestBody Map<String, Object> body) {
        requireManageable(agentId);
        String name = str(body.get("name")).trim();
        String description = str(body.get("description")).trim();
        String content = str(body.get("content"));
        if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 必填");
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 必填");
        if (!store.create(agentId, name, description, content)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "skill 已存在: " + name);
        }
        registry.invalidate(agentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(skillMap(new SkillStore.Skill(name, description, content.strip(), "workspace")));
    }

    /** PUT /skills/{name} — update skill, snapshotting the old revision. */
    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String agentId, @PathVariable String name,
                                    @RequestBody Map<String, Object> body) {
        requireManageable(agentId);
        if (store.get(agentId, name) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill 不存在");
        }
        String description = str(body.get("description")).trim();
        String content = str(body.get("content"));
        if (content.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 必填");
        String note = str(body.get("note"));
        SkillStore.Skill s = store.save(agentId, name, description, content, note);
        registry.invalidate(agentId);
        return ResponseEntity.ok(skillMap(s));
    }

    /** DELETE /skills/{name} — remove the skill directory. */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String agentId, @PathVariable String name) {
        requireManageable(agentId);
        if (!store.delete(agentId, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill 不存在");
        }
        registry.invalidate(agentId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** POST /skills/{name}/versions — explicitly snapshot the current revision. */
    @PostMapping("/{name}/versions")
    public ResponseEntity<?> snapshot(@PathVariable String agentId, @PathVariable String name,
                                      @RequestBody(required = false) Map<String, Object> body) {
        requireManageable(agentId);
        String note = body == null ? "" : str(body.get("note"));
        int v = store.snapshotNow(agentId, name, note);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", v));
    }

    /** GET /skills/{name}/versions — revision history (newest first). */
    @GetMapping("/{name}/versions")
    public ResponseEntity<?> versions(@PathVariable String agentId, @PathVariable String name) {
        requireManageable(agentId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillStore.Version v : store.versions(agentId, name)) out.add(versionMap(v));
        return ResponseEntity.ok(out);
    }

    /** GET /skills/{name}/versions/{version} — one revision's full content. */
    @GetMapping("/{name}/versions/{version}")
    public ResponseEntity<?> version(@PathVariable String agentId, @PathVariable String name,
                                     @PathVariable int version) {
        requireManageable(agentId);
        SkillStore.Version v = store.version(agentId, name, version);
        if (v == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        return ResponseEntity.ok(versionMap(v));
    }

    /** POST /skills/{name}/versions/{version}/rollback — restore a revision as active. */
    @PostMapping("/{name}/versions/{version}/rollback")
    public ResponseEntity<?> rollback(@PathVariable String agentId, @PathVariable String name,
                                      @PathVariable int version) {
        requireManageable(agentId);
        SkillStore.Skill s = store.rollback(agentId, name, version);
        registry.invalidate(agentId);
        return ResponseEntity.ok(skillMap(s));
    }

    /** Invalid skill names / paths are client errors, not server faults. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
