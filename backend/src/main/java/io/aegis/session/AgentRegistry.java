package io.aegis.session;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.aegis.agent.Agent;
import io.aegis.agent.AgentRepository;
import io.aegis.skill.SkillToolGroupStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily builds and caches one HarnessAgent per Aegis agent. Each agent points
 * at its own workspace directory so tools/memory/skills are isolated by agent.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final AgentRepository agents;
    private final SkillToolGroupStore toolGroups;
    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    private final Model defaultModel;

    public AgentRegistry(AgentRepository agents, SkillToolGroupStore toolGroups) {
        this.agents = agents;
        this.toolGroups = toolGroups;
        this.defaultModel = buildModel();
    }

    private Model buildModel() {
        // trim() guards against stray \r when env vars come from a CRLF .env sourced by shell.
        String baseUrl = System.getenv().getOrDefault("AEGIS_MODEL_BASE_URL", "https://api.openai.com/v1").trim();
        String apiKey = System.getenv().getOrDefault("AEGIS_MODEL_API_KEY", "sk-local-placeholder").trim();
        String modelName = System.getenv().getOrDefault("AEGIS_MODEL_NAME", "gpt-4o-mini").trim();
        log.info("Building OpenAI-compatible model: baseUrl={}, model={}", baseUrl, modelName);
        // gpu-qwen3.8-27b is a thinking model. Thinking is enabled by default so the
        // client can show reasoning before tool calls; set AEGIS_MODEL_THINKING=false
        // to restore the old "no visible reasoning" behaviour. TitleService still
        // disables thinking explicitly for its short auxiliary call.
        boolean thinking = Boolean.parseBoolean(
                System.getenv().getOrDefault("AEGIS_MODEL_THINKING", "true").trim());
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of(
                        "chat_template_kwargs", Map.of("enable_thinking", thinking)))
                .build();
        log.info("Model thinking {}", thinking ? "enabled" : "disabled");
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .generateOptions(options)
                .build();
    }

    public HarnessAgent get(String agentId) {
        return cache.computeIfAbsent(agentId, this::build);
    }

    /** The shared chat model, reused by auxiliary services (e.g. session title generation). */
    public io.agentscope.core.model.Model defaultModel() {
        return defaultModel;
    }

    private HarnessAgent build(String agentId) {
        Agent meta = agents.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown agent: " + agentId));
        String wsRoot = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        Path workspace = Path.of(wsRoot, agentId);
        log.info("Building HarnessAgent for {} with workspace {}", agentId, workspace.toAbsolutePath());
        HarnessAgent agent = HarnessAgent.builder()
                .model(defaultModel)
                .workspace(workspace)
                .sysPrompt(meta.sysPrompt == null ? "You are a helpful assistant." : meta.sysPrompt)
                .build();
        registerToolGroups(agentId, agent);
        return agent;
    }

    /**
     * Replay persisted SkillToolGroups into the freshly built agent's Toolkit.
     * Uses createSkillToolGroup + addToolToGroup (not registerToolGroup with a
     * pre-built group): the framework only populates its tool→group index via
     * addToolToGroup, so the latter silently leaves grouped tools unindexed.
     */
    private void registerToolGroups(String agentId, HarnessAgent agent) {
        List<SkillToolGroupStore.Group> groups = toolGroups.list(agentId);
        if (groups.isEmpty()) return;
        var tk = agent.getToolkit();
        if (tk == null) { log.warn("Toolkit unavailable; skipping tool groups for {}", agentId); return; }
        for (SkillToolGroupStore.Group g : groups) {
            try {
                // active=false: a SkillToolGroup is activated by the framework when
                // its bound skill is loaded, not by a persisted flag.
                tk.createSkillToolGroup(g.name(),
                        g.description() == null ? "" : g.description(), false, g.activateOnSkill());
                for (String tool : g.tools()) tk.addToolToGroup(g.name(), tool);
                log.info("Registered tool group {} for agent {} -> skill {} ({} tools)",
                        g.name(), agentId, g.activateOnSkill(), g.tools().size());
            } catch (Exception e) {
                log.warn("Failed to register tool group {} for {}: {}", g.name(), agentId, e.getMessage());
            }
        }
    }

    /**
     * Build a throwaway HarnessAgent over an isolated temp workspace, used only to
     * read the framework's real built-in tool catalog. It deliberately skips any
     * agent-specific tools.json / MCP config so the list reflects framework defaults.
     * Caller is responsible for {@link HarnessAgent#close()}.
     */
    public HarnessAgent buildCatalogAgent() {
        Path ws;
        try {
            ws = java.nio.file.Files.createTempDirectory("aegis-tool-catalog");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create temp workspace for tool catalog", e);
        }
        log.debug("Building catalog HarnessAgent with workspace {}", ws.toAbsolutePath());
        return HarnessAgent.builder()
                .model(defaultModel)
                .workspace(ws)
                .sysPrompt("tool catalog probe")
                .build();
    }

    /** Invalidate cached agent after config change. */
    public void invalidate(String agentId) {
        cache.remove(agentId);
    }
}
