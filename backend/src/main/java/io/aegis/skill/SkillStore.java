package io.aegis.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill persistence: the active skill is a file on disk
 * ({@code workspace/skills/<name>/SKILL.md}) so the framework loads it natively
 * and the Workspace tab can browse it; past revisions are snapshotted in SQLite.
 */
@Service
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    /** Matches the framework MarkdownSkillParser frontmatter shape. */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^\\uFEFF?---\\s*[\\r\\n]+(.*?)(?<=[\\r\\n])---(?=[ \\t]*(?:[\\r\\n]|\\z))(?:\\s*[\\r\\n]+)?(.*)$",
            Pattern.DOTALL);

    /** Kebab-case / underscore skill dir names; no path separators or traversal. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final JdbcTemplate jdbc;

    public SkillStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Skill(String name, String description, String content, String source) {}
    public record Version(int version, String description, String content, String savedAt, String note) {}

    // ---------- path helpers ----------

    public Path skillsRoot(String agentId) {
        String wsRoot = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        return Path.of(wsRoot, agentId, "skills").normalize().toAbsolutePath();
    }

    private Path skillDir(String agentId, String name) {
        if (!SAFE_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("非法 skill 名称");
        }
        return skillsRoot(agentId).resolve(name).normalize().toAbsolutePath();
    }

    private Path skillFile(String agentId, String name) {
        Path root = skillsRoot(agentId);
        Path f = skillDir(agentId, name).resolve("SKILL.md");
        if (!f.startsWith(root)) throw new IllegalArgumentException("路径越界");
        return f;
    }

    // ---------- frontmatter ----------

    /** Parse SKILL.md into name/description/content. Falls back to first # heading if no frontmatter. */
    public Skill parse(String raw, String fallbackName) {
        String name = fallbackName;
        String description = "";
        String body = raw == null ? "" : raw;
        Matcher m = FRONTMATTER.matcher(body);
        if (m.matches()) {
            String yaml = m.group(1);
            body = m.group(2);
            name = yamlValue(yaml, "name", fallbackName);
            description = yamlValue(yaml, "description", "");
        }
        // tolerate legacy SKILL.md without frontmatter: derive description from first heading/text
        if (description.isBlank()) {
            for (String line : body.split("\\R")) {
                String t = line.trim().replaceFirst("^#+\\s*", "");
                if (!t.isBlank()) { description = t; break; }
            }
        }
        return new Skill(name, description, body.strip(), "workspace");
    }

    private static String yamlValue(String yaml, String key, String def) {
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.*?)\\s*$");
        Matcher m = p.matcher(yaml);
        if (!m.find()) return def;
        String v = m.group(1).trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isBlank() ? def : v;
    }

    /** Render in the exact shape produced by the framework's SkillFileSystemHelper. */
    public String render(String name, String description, String content) {
        StringBuilder b = new StringBuilder();
        b.append("---\n")
         .append("name: ").append(sanitizeScalar(name)).append('\n')
         .append("description: ").append(sanitizeScalar(description)).append('\n')
         .append("---\n\n")
         .append(content == null ? "" : content.strip())
         .append('\n');
        return b.toString();
    }

    private static String sanitizeScalar(String v) {
        if (v == null) return "";
        // quote when value contains YAML-special leading chars or ': ' sequences
        if (v.isBlank() || v.startsWith("-") || v.startsWith("?") || v.startsWith("#")
                || v.contains(": ") || v.contains("\n") || v.contains("\"") || v.contains("'")) {
            return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
        }
        return v;
    }

    // ---------- file CRUD ----------

    public List<Skill> list(String agentId) {
        Path root = skillsRoot(agentId);
        List<Skill> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).sorted().toList()) {
                Path f = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(f)) continue;
                String raw = Files.readString(f, StandardCharsets.UTF_8);
                out.add(parse(raw, dir.getFileName().toString()));
            }
        } catch (Exception e) {
            log.warn("list skills failed for {}: {}", agentId, e.getMessage());
        }
        return out;
    }

    public Skill get(String agentId, String name) {
        Path f = skillFile(agentId, name);
        if (!Files.isRegularFile(f)) return null;
        try {
            return parse(Files.readString(f, StandardCharsets.UTF_8), name);
        } catch (Exception e) {
            throw new IllegalStateException("读取 skill 失败: " + e.getMessage());
        }
    }

    /** Save skill content, snapshotting the previous revision into version history. */
    public Skill save(String agentId, String name, String description, String content, String note) {
        Path f = skillFile(agentId, name);
        try {
            Files.createDirectories(f.getParent());
            if (Files.exists(f)) {
                Skill old = parse(Files.readString(f, StandardCharsets.UTF_8), name);
                snapshot(agentId, name, old.description(), old.content(), note == null ? "更新前自动快照" : note);
            }
            Files.writeString(f, render(name, description, content), StandardCharsets.UTF_8);
            return new Skill(name, description, content == null ? "" : content.strip(), "workspace");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存 skill 失败: " + e.getMessage());
        }
    }

    /** Create only if absent; returns false when the skill already exists. */
    public boolean create(String agentId, String name, String description, String content) {
        Path f = skillFile(agentId, name);
        if (Files.exists(f)) return false;
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, render(name, description, content), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("创建 skill 失败: " + e.getMessage());
        }
    }

    public boolean delete(String agentId, String name) {
        Path dir = skillDir(agentId, name);
        Path root = skillsRoot(agentId);
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) return false;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (Exception e) {
            throw new IllegalStateException("删除 skill 失败: " + e.getMessage());
        }
        // Drop revision history with the skill; keeping orphans would leak disk and confuse audits.
        jdbc.update("DELETE FROM skill_version WHERE agent_id=? AND skill_name=?", agentId, name);
        return true;
    }

    // ---------- versions ----------

    private void snapshot(String agentId, String name, String description, String content, String note) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM skill_version WHERE agent_id=? AND skill_name=?",
                Integer.class, agentId, name);
        int v = next == null ? 1 : next;
        jdbc.update("INSERT INTO skill_version(agent_id,skill_name,version,description,content,note) VALUES(?,?,?,?,?,?)",
                agentId, name, v, description, content, note);
    }

    /** Explicitly snapshot the current revision (user-triggered "save version"). */
    public int snapshotNow(String agentId, String name, String note) {
        Skill cur = get(agentId, name);
        if (cur == null) throw new IllegalArgumentException("skill 不存在: " + name);
        snapshot(agentId, name, cur.description(), cur.content(), note == null ? "手动保存版本" : note);
        return jdbc.queryForObject(
                "SELECT MAX(version) FROM skill_version WHERE agent_id=? AND skill_name=?",
                Integer.class, agentId, name);
    }

    public List<Version> versions(String agentId, String name) {
        return jdbc.query("SELECT version,description,content,saved_at,note FROM skill_version "
                        + "WHERE agent_id=? AND skill_name=? ORDER BY version DESC",
                (rs, i) -> new Version(rs.getInt("version"), rs.getString("description"),
                        rs.getString("content"), rs.getString("saved_at"), rs.getString("note")),
                agentId, name);
    }

    public Version version(String agentId, String name, int version) {
        List<Version> l = jdbc.query("SELECT version,description,content,saved_at,note FROM skill_version "
                        + "WHERE agent_id=? AND skill_name=? AND version=?",
                (rs, i) -> new Version(rs.getInt("version"), rs.getString("description"),
                        rs.getString("content"), rs.getString("saved_at"), rs.getString("note")),
                agentId, name, version);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Restore a version by writing it back as the active SKILL.md (also snapshots current first). */
    public Skill rollback(String agentId, String name, int version) {
        Version v = version(agentId, name, version);
        if (v == null) throw new IllegalArgumentException("版本不存在: " + version);
        return save(agentId, name, v.description(), v.content(), "回滚到 v" + version);
    }
}
