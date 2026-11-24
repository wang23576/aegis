package io.aegis.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Workspace scaffold shared by DataBootstrap (claw seed) and AgentController
 * (agent create/update). Keeps the AGENTS.md behavioral contract in one place.
 */
public final class WorkspaceScaffold {

    private WorkspaceScaffold() {}

    /**
     * Full AGENTS.md content: the agent's own persona plus platform-wide
     * behavioral rules. Fixes two real production issues:
     *  - replies drifting into English (no language constraint before)
     *  - answering "what files/dirs exist" from stale memory instead of
     *    actually running tools (memory must never substitute live queries)
     */
    public static String agentsMd(String agentId, String sysPrompt) {
        String persona = (sysPrompt == null || sysPrompt.isBlank())
                ? "你是一个乐于助人的通用助手。请准确、简洁地回答问题。"
                : sysPrompt;
        return """
                # %s

                %s

                ## 语言要求

                - 始终使用简体中文回复，除非用户明确要求使用其他语言。
                - 代码、命令、工具名、文件路径保持原样，不强行翻译。

                ## 工具使用规则（重要）

                - 涉及**当前实际状态**的问题——目录里有什么、文件是否存在、文件内容、系统时间、命令输出等——必须**真实调用工具查询后回答**，禁止凭记忆或推测回答。
                - 长期记忆（memory_search）只用于回忆**用户偏好和历史事实**（例如用户的称呼、代号、既往约定）。记忆中的环境/文件快照会过时，一律以工具实时查询结果为准，不要把记忆里的目录快照当作现状。
                - 不确定就先查：列目录用 list_files，读文件用 read_file，搜内容用 grep_files，执行命令用 execute，联网查资料用 web_search / web_fetch。
                - 工具查询失败或结果为空时，如实告知用户，不要编造结果。

                ## 工具输出解读约定（重要）

                - `list_files` 输出中以 `/` 开头的条目（例如 `/aegis.jar`）**不是**所列目录里的真实文件：它们是运行时把项目目录（`/app`）的内容以虚拟路径形式合并显示出来的结果，实际文件位于 `/app` 下。
                - 因此判断"某目录下到底有哪些文件"时，如果 `list_files` 结果里混有以 `/` 开头的条目，**必须再用 `execute` 执行 `ls -la <目录>` 交叉验证**，以真实 shell 输出为准，并向用户说明两者差异。
                - `.agentscope-runtime/` 是运行时保留目录（会话 journal），工具无法访问其内容，属正常现象，不要反复尝试，也不要报为异常。
                """.formatted(agentId, persona);
    }

    /** Creates the workspace directory layout for an agent and writes AGENTS.md + tools.json. */
    public static void ensure(Path ws, String agentId, String sysPrompt, String toolsJson) throws Exception {
        Files.createDirectories(ws.resolve("skills/example-skill"));
        Files.createDirectories(ws.resolve("subagents"));
        Files.createDirectories(ws.resolve("memory"));
        Files.writeString(ws.resolve("AGENTS.md"), agentsMd(agentId, sysPrompt));
        if (toolsJson != null) {
            Files.writeString(ws.resolve("tools.json"), toolsJson);
        }
        Files.writeString(ws.resolve("skills/example-skill/SKILL.md"),
                "# Example skill\n\nA starter placeholder skill.\n");
        Files.writeString(ws.resolve("subagents/README.md"), "# Subagents\n\nSub-agent definitions go here.\n");
    }
}
