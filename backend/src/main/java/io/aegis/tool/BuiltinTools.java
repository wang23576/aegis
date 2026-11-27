package io.aegis.tool;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.aegis.session.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Catalog of built-in tools exposed by the framework's HarnessAgent toolkit.
 *
 * <p>The framework does not ship a static "builtins" metadata table, so instead
 * of hard-coding a (quickly stale) list in Aegis, we ask a throwaway HarnessAgent
 * for its real {@link Toolkit} and read each tool's name/description through the
 * {@link AgentTool} API. This keeps the Tool tab in sync with the framework
 * version actually on the classpath.</p>
 *
 * <p>Category is an Aegis-side grouping for the UI only (the framework has no
 * such concept); readOnly is inferred from a conservative name set since the
 * framework does not expose mutability metadata either.</p>
 */
@Service
public class BuiltinTools {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTools.class);

    public record ToolInfo(String name, String category, boolean readOnly, String description) {}

    /** UI-only grouping. Unknown tools fall back to "other" so new framework tools still show up. */
    private static final Map<String, String> CATEGORY = new LinkedHashMap<>();
    private static final Set<String> READ_ONLY = new HashSet<>();

    static {
        for (String n : List.of("read_file", "write_file", "edit_file", "list_files",
                "grep_files", "glob_files", "view_text_file", "list_directory",
                "write_text_file", "insert_text_file")) CATEGORY.put(n, "filesystem");
        CATEGORY.put("execute", "shell");
        CATEGORY.put("execute_shell_command", "shell");
        CATEGORY.put("web_fetch", "web");
        CATEGORY.put("web_search", "web");
        for (String n : List.of("memory_search", "memory_get", "memory_save")) CATEGORY.put(n, "memory");
        for (String n : List.of("thread_search", "thread_list", "thread_history")) CATEGORY.put(n, "session");
        for (String n : List.of("agent_spawn", "agent_send", "agent_list", "agent_release",
                "agent_generate", "sessions_spawn", "sessions_send", "sessions_list",
                "sessions_history", "sessions_pending_completions")) CATEGORY.put(n, "subagent");
        for (String n : List.of("skill_manage", "propose_skill", "load_skill_through_path")) CATEGORY.put(n, "skill");
        for (String n : List.of("task_output", "task_cancel", "task_list", "wait_async_results",
                "todo_write", "task_requirement_propose")) CATEGORY.put(n, "task");
        CATEGORY.put("deliver_artifact", "artifact");
        for (String n : List.of("plan_enter", "plan_write", "plan_exit")) CATEGORY.put(n, "plan");
        CATEGORY.put("tool_search", "tool");

        for (String n : List.of("read_file", "list_files", "grep_files", "glob_files",
                "view_text_file", "list_directory", "web_fetch", "web_search",
                "memory_search", "memory_get", "thread_search", "thread_list", "thread_history",
                "agent_list", "sessions_list", "sessions_history", "sessions_pending_completions",
                "task_list", "task_output", "tool_search", "load_skill_through_path")) READ_ONLY.add(n);
    }

    private final AgentRegistry registry;

    public BuiltinTools(AgentRegistry registry) {
        this.registry = registry;
    }

    /** Read the real toolkit from a framework-built agent (no MCP, no allow/deny filtering). */
    private List<ToolInfo> fromFramework() {
        List<ToolInfo> out = new ArrayList<>();
        HarnessAgent probe = null;
        try {
            probe = registry.buildCatalogAgent();
            Toolkit tk = probe.getToolkit();
            if (tk == null) {
                log.warn("HarnessAgent toolkit unavailable; built-in catalog will be empty");
                return out;
            }
            Set<String> names = new TreeSet<>(tk.getToolNames());
            for (String name : names) {
                AgentTool t = tk.getTool(name);
                if (t == null) continue;
                String desc = t.getDescription();
                out.add(new ToolInfo(
                        name,
                        CATEGORY.getOrDefault(name, "other"),
                        READ_ONLY.contains(name),
                        desc == null ? "" : desc));
            }
        } catch (Exception e) {
            log.warn("Failed to read built-in tool catalog from framework: {}", e.getMessage());
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (Exception ignore) { }
            }
        }
        return out;
    }

    /** Fresh catalog each call so framework/tool changes are reflected after config edits. */
    public List<ToolInfo> all() { return fromFramework(); }

    public List<Map<String, Object>> catalog() {
        return all().stream().map(t -> Map.<String, Object>of(
                "name", t.name(), "category", t.category(),
                "readOnly", t.readOnly(), "description", t.description(), "builtin", true)).toList();
    }

    public boolean isBuiltin(String name) {
        return all().stream().anyMatch(t -> t.name().equals(name));
    }
}
