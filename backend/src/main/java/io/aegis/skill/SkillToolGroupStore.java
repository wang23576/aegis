package io.aegis.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Persistence for SkillToolGroup definitions.
 *
 * <p>A SkillToolGroup is a named bundle of tools that the framework activates when
 * a given skill is loaded. The framework itself has no persistence layer for these
 * groups, so Aegis stores them here and replays them into each HarnessAgent's
 * {@code Toolkit} when the agent is built (see AgentRegistry.build).</p>
 */
@Service
public class SkillToolGroupStore {

    /** Same safe-name rule as skills: kebab/underscore identifiers, no traversal. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    /** Tool names from the framework / MCP are snake_case identifiers. */
    private static final Pattern SAFE_TOOL = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public SkillToolGroupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Group(String name, String description, String activateOnSkill,
                        List<String> tools, String createdAt, String updatedAt) {}

    /** Validate a group definition. Throws IllegalArgumentException with a human message. */
    public void validate(String name, String activateOnSkill, List<String> tools) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("分组名称只能包含字母、数字、点、下划线和连字符，且以字母数字开头");
        }
        if (activateOnSkill == null || activateOnSkill.isBlank()) {
            throw new IllegalArgumentException("激活技能必填");
        }
        if (!SAFE_NAME.matcher(activateOnSkill).matches()) {
            throw new IllegalArgumentException("激活技能名称非法");
        }
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个工具");
        }
        Set<String> seen = new HashSet<>();
        for (String t : tools) {
            if (t == null || !SAFE_TOOL.matcher(t).matches()) {
                throw new IllegalArgumentException("非法工具名: " + t);
            }
            if (!seen.add(t)) throw new IllegalArgumentException("工具重复: " + t);
        }
    }

    public List<Group> list(String agentId) {
        return jdbc.query("SELECT name,description,activate_on_skill,tools_json,created_at,updated_at "
                        + "FROM skill_tool_group WHERE agent_id=? ORDER BY name",
                (rs, i) -> new Group(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("activate_on_skill"),
                        parseTools(rs.getString("tools_json")),
                        rs.getString("created_at"),
                        rs.getString("updated_at")),
                agentId);
    }

    public Group get(String agentId, String name) {
        List<Group> l = jdbc.query("SELECT name,description,activate_on_skill,tools_json,created_at,updated_at "
                        + "FROM skill_tool_group WHERE agent_id=? AND name=?",
                (rs, i) -> new Group(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("activate_on_skill"),
                        parseTools(rs.getString("tools_json")),
                        rs.getString("created_at"),
                        rs.getString("updated_at")),
                agentId, name);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Create a group; returns false if a group with the same name already exists. */
    public boolean create(String agentId, String name, String description,
                          String activateOnSkill, List<String> tools) {
        validate(name, activateOnSkill, tools);
        // Explicit existence check: SQLite JDBC reports UNIQUE violations as
        // UncategorizedSQLException, so relying on DuplicateKeyException is unreliable.
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_tool_group WHERE agent_id=? AND name=?",
                Integer.class, agentId, name);
        if (exists != null && exists > 0) return false;
        jdbc.update("INSERT INTO skill_tool_group(agent_id,name,description,activate_on_skill,tools_json) "
                        + "VALUES(?,?,?,?,?)",
                agentId, name, description == null ? "" : description.trim(),
                activateOnSkill.trim(), writeTools(tools));
        return true;
    }

    /** Update an existing group; returns false if it does not exist. */
    public boolean update(String agentId, String name, String description,
                          String activateOnSkill, List<String> tools) {
        validate(name, activateOnSkill, tools);
        int n = jdbc.update("UPDATE skill_tool_group SET description=?,activate_on_skill=?,tools_json=?, "
                        + "updated_at=datetime('now') WHERE agent_id=? AND name=?",
                description == null ? "" : description.trim(), activateOnSkill.trim(),
                writeTools(tools), agentId, name);
        return n > 0;
    }

    public boolean delete(String agentId, String name) {
        return jdbc.update("DELETE FROM skill_tool_group WHERE agent_id=? AND name=?", agentId, name) > 0;
    }

    private String writeTools(List<String> tools) {
        try { return json.writeValueAsString(tools); }
        catch (Exception e) { throw new IllegalStateException("序列化工具列表失败: " + e.getMessage()); }
    }

    private List<String> parseTools(String s) {
        if (s == null || s.isBlank()) return List.of();
        try { return json.readValue(s, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }
}
