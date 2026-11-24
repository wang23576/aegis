package io.aegis.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AgentRepository {

    public static final String DEFAULT_TOOLS_JSON = """
        {
          "allow": ["read_file","write_file","edit_file","list_files","grep_files","glob_files",
                    "execute","web_fetch","web_search","memory_search","memory_get","memory_save",
                    "thread_search","thread_list","thread_history"],
          "deny": [],
          "mcpServers": {}
        }
        """;

    private static final RowMapper<Agent> MAP = (rs, n) -> {
        Agent a = new Agent();
        a.id = rs.getString("id");
        a.name = rs.getString("name");
        a.description = rs.getString("description");
        a.ownerId = rs.getLong("owner_id");
        a.builtin = rs.getInt("builtin") == 1;
        a.sysPrompt = rs.getString("sys_prompt");
        a.toolsJson = rs.getString("tools_json");
        a.model = rs.getString("model");
        a.createdAt = rs.getString("created_at");
        return a;
    };

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Agent> findById(String id) {
        List<Agent> r = jdbc.query("SELECT * FROM agent WHERE id=?", MAP, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** All agents visible to a user: builtins + own. Super admins see all. */
    public List<Agent> visibleTo(long userId, boolean superAdmin) {
        if (superAdmin) {
            return jdbc.query("SELECT * FROM agent ORDER BY builtin DESC, created_at", MAP);
        }
        return jdbc.query("SELECT * FROM agent WHERE builtin=1 OR owner_id=? ORDER BY builtin DESC, created_at", MAP, userId);
    }

    /** Agents manageable by a user: own (incl. builtin if owned). Super admins see all. */
    public List<Agent> manageableBy(long userId, boolean superAdmin) {
        if (superAdmin) {
            return jdbc.query("SELECT * FROM agent ORDER BY builtin DESC, created_at", MAP);
        }
        return jdbc.query("SELECT * FROM agent WHERE owner_id=? ORDER BY created_at", MAP, userId);
    }

    public void create(String id, String name, String description, long ownerId, boolean builtin,
                       String sysPrompt, String toolsJson, String model) {
        jdbc.update("INSERT INTO agent(id,name,description,owner_id,builtin,sys_prompt,tools_json,model) VALUES(?,?,?,?,?,?,?,?)",
                id, name, description, ownerId, builtin ? 1 : 0, sysPrompt, toolsJson, model);
    }

    public int update(String id, String name, String description, String sysPrompt, String toolsJson, String model) {
        return jdbc.update("UPDATE agent SET name=?,description=?,sys_prompt=?,tools_json=?,model=? WHERE id=?",
                name, description, sysPrompt, toolsJson, model, id);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM agent WHERE id=?", id);
    }
}
