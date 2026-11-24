package io.aegis.tool;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Registry of agentscope builtin tools. The framework exposes no isBuiltin
 * metadata, so Aegis maintains the authoritative list for UI badges.
 */
@Service
public class BuiltinTools {

    public record ToolInfo(String name, String category, boolean readOnly, String description) {}

    private static final List<ToolInfo> ALL = List.of(
            new ToolInfo("read_file", "filesystem", true, "Read file contents"),
            new ToolInfo("write_file", "filesystem", false, "Create a new file"),
            new ToolInfo("edit_file", "filesystem", false, "Edit file contents"),
            new ToolInfo("list_files", "filesystem", true, "List directory"),
            new ToolInfo("grep_files", "filesystem", true, "Search file contents"),
            new ToolInfo("glob_files", "filesystem", true, "Find files by pattern"),
            new ToolInfo("execute", "shell", false, "Run shell command"),
            new ToolInfo("web_fetch", "web", true, "Fetch a web page"),
            new ToolInfo("web_search", "web", true, "Search the web"),
            new ToolInfo("memory_search", "memory", true, "Search long-term memory"),
            new ToolInfo("memory_get", "memory", true, "Read memory file"),
            new ToolInfo("memory_save", "memory", false, "Save long-term memory"),
            new ToolInfo("thread_search", "session", true, "Search conversation history"),
            new ToolInfo("thread_list", "session", true, "List conversations"),
            new ToolInfo("thread_history", "session", true, "Read full conversation")
    );

    private static final Set<String> NAMES = new HashSet<>(ALL.stream().map(ToolInfo::name).toList());

    public List<Map<String, Object>> catalog() {
        return ALL.stream().map(t -> Map.<String, Object>of(
                "name", t.name(), "category", t.category(),
                "readOnly", t.readOnly(), "description", t.description(), "builtin", true)).toList();
    }

    public boolean isBuiltin(String name) { return NAMES.contains(name); }
    public List<ToolInfo> all() { return ALL; }
}
