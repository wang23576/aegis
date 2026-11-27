package io.aegis.agent;

import io.aegis.auth.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only workspace browsing for agents the current user can manage
 * (Harness management: Workspace tab). Mirrors paw's AgentWorkspaceController
 * but strictly read-only — edits happen via the agent itself or Skills tabs.
 *
 * All paths are relative to {@code ${AEGIS_WORKSPACE_ROOT}/{agentId}} and are
 * guarded against traversal outside that root.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class WorkspaceFileController {

    /** Generous cap so JSONL transcripts / AGENTS.md fit; larger files get a placeholder. */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;

    private final AgentRepository agents;

    public WorkspaceFileController(AgentRepository agents) {
        this.agents = agents;
    }

    private boolean canManage(Agent a) {
        AuthContext.CurrentUser c = AuthContext.get();
        return c.isSuperAdmin() || Objects.equals(a.ownerId, c.id);
    }

    private Agent requireManageable(String agentId) {
        Agent a = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!canManage(a)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return a;
    }

    private Path workspaceRoot(String agentId) {
        String root = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        return Path.of(root, agentId).normalize().toAbsolutePath();
    }

    /** Resolves a workspace-relative path, rejecting traversal outside the root. */
    private static Path guard(Path workspace, String rel) {
        Path resolved = workspace.resolve(rel).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        return resolved;
    }

    /**
     * GET /files?path= — one directory level only (lazy loading).
     * Returns direct children of {@code path} ("" = workspace root), each dir
     * tagged with hasChildren so the UI can show an expand arrow without
     * walking the whole tree. Keeps large workspaces cheap: one listing per click.
     */
    @GetMapping("/files")
    public ResponseEntity<?> listDirectory(
            @PathVariable String agentId,
            @RequestParam(name = "path", defaultValue = "") String path) {
        requireManageable(agentId);
        Path ws = workspaceRoot(agentId);
        Path dir = guard(ws, path);
        if (!Files.isDirectory(dir)) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(listChildren(ws, dir));
    }

    /** GET /file?path= — UTF-8 content of one file (binary/oversized get placeholders). */
    @GetMapping("/file")
    public ResponseEntity<?> readFile(
            @PathVariable String agentId,
            @RequestParam("path") String path) {
        requireManageable(agentId);
        Path ws = workspaceRoot(agentId);
        Path target = guard(ws, path);
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        try {
            long size = Files.size(target);
            out.put("size", size);
            if (size > MAX_FILE_SIZE) {
                out.put("content", "");
                out.put("truncated", true);
                out.put("message", "文件过大（" + size + " 字节），无法预览");
            } else if (looksBinary(target)) {
                out.put("content", "");
                out.put("binary", true);
                out.put("message", "二进制文件，暂不支持预览");
            } else {
                String content = Files.readString(target);
                out.put("content", content);
                out.put("truncated", false);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Read failed: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** GET /summary — workspace path + quick counts for the header bar. */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@PathVariable String agentId) {
        requireManageable(agentId);
        Path ws = workspaceRoot(agentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", agentId);
        m.put("workspacePath", ws.toString());
        m.put("exists", Files.isDirectory(ws));
        m.put("agentsMdExists", Files.isRegularFile(ws.resolve("AGENTS.md")));
        m.put("memoryMdExists", Files.isRegularFile(ws.resolve("MEMORY.md")));
        m.put("skillCount", countEntries(ws.resolve("skills"), true));
        m.put("subagentCount", countMdFiles(ws.resolve("subagents")));
        m.put("dailyMemoryCount", countMdFiles(ws.resolve("memory")));
        return ResponseEntity.ok(m);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /** Lists only the direct children of {@code dir} — one level, no recursion. */
    private static List<Map<String, Object>> listChildren(Path base, Path dir) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) {
                boolean isDir = Files.isDirectory(entry);
                String name = entry.getFileName().toString();
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", name);
                node.put("path", base.relativize(entry).toString().replace('\\', '/'));
                node.put("type", isDir ? "dir" : "file");
                if (isDir) {
                    node.put("hasChildren", hasAnyChild(entry));
                } else {
                    try { node.put("size", Files.size(entry)); } catch (IOException ignored) { node.put("size", 0); }
                }
                out.add(node);
            }
        } catch (IOException e) {
            // skip unreadable dirs
        }
        out.sort(Comparator
                .comparing((Map<String, Object> n) -> !"dir".equals(n.get("type")))
                .thenComparing(n -> String.valueOf(n.get("name")), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Cheap existence check for any child entry (short-circuits on first hit). */
    private static boolean hasAnyChild(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /** Samples the first bytes; many control chars → treat as binary. */
    private static boolean looksBinary(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] buf = in.readNBytes(1024);
            int ctrl = 0;
            for (byte b : buf) {
                if (b == 0) return true;
                // Java bytes are signed: UTF-8 multibyte sequences (>= 0x80) come
                // through as negative values — they are text, not control chars.
                if (b >= 0 && (b < 0x09 || (b > 0x0D && b < 0x20))) ctrl++;
            }
            return buf.length > 0 && ctrl > buf.length / 8;
        } catch (IOException e) {
            return true;
        }
    }

    private static int countEntries(Path dir, boolean dirOnly) {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> !dirOnly || Files.isDirectory(p)).count();
        } catch (IOException e) { return 0; }
    }

    private static int countMdFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".md")).count();
        } catch (IOException e) { return 0; }
    }
}
