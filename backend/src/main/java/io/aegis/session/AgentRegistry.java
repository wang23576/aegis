package io.aegis.session;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.aegis.agent.Agent;
import io.aegis.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    private final Model defaultModel;

    public AgentRegistry(AgentRepository agents) {
        this.agents = agents;
        this.defaultModel = buildModel();
    }

    private Model buildModel() {
        // trim() guards against stray \r when env vars come from a CRLF .env sourced by shell.
        String baseUrl = System.getenv().getOrDefault("AEGIS_MODEL_BASE_URL", "https://api.openai.com/v1").trim();
        String apiKey = System.getenv().getOrDefault("AEGIS_MODEL_API_KEY", "sk-local-placeholder").trim();
        String modelName = System.getenv().getOrDefault("AEGIS_MODEL_NAME", "gpt-4o-mini").trim();
        log.info("Building OpenAI-compatible model: baseUrl={}, model={}", baseUrl, modelName);
        // Mirror paw's TalkwebModelConfig: gpu-qwen3.8-27b is a thinking model; disable
        // chain-of-thought via the vLLM chat template so replies return directly (no endless spinner).
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of(
                        "chat_template_kwargs", Map.of("enable_thinking", false)))
                .build();
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
        return HarnessAgent.builder()
                .model(defaultModel)
                .workspace(workspace)
                .sysPrompt(meta.sysPrompt == null ? "You are a helpful assistant." : meta.sysPrompt)
                .build();
    }

    /** Invalidate cached agent after config change. */
    public void invalidate(String agentId) {
        cache.remove(agentId);
    }
}
