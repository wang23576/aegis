package io.aegis.session;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Generates a session title from the first user message using the same OpenAI-compatible
 * model as chat (DSH session-title-first-prompt-llm equivalent). One short auxiliary
 * call; on any failure falls back to a deterministic truncation of the first prompt.
 */
@Service
public class TitleService {

    private static final Logger log = LoggerFactory.getLogger(TitleService.class);

    private final io.agentscope.core.model.Model model;
    private final JdbcTemplate jdbc;

    public TitleService(AgentRegistry registry, JdbcTemplate jdbc) {
        this.model = registry.defaultModel();
        this.jdbc = jdbc;
    }

    /**
     * Generates and persists a title for the session from its first user message.
     * Best-effort: failures keep the existing title (no exception escapes).
     */
    public void generateFor(String sessionId) {
        try {
            List<String> firsts = jdbc.query(
                    "SELECT content FROM chat_message WHERE session_id=? AND role='user' ORDER BY id LIMIT 1",
                    (rs, n) -> rs.getString(1), sessionId);
            if (firsts.isEmpty()) return;
            String title = generate(firsts.get(0));
            jdbc.update("UPDATE chat_session SET title=? WHERE id=?", title, sessionId);
            log.info("Session {} titled: {}", sessionId, title);
        } catch (Exception e) {
            log.warn("Title generation failed for {}: {}", sessionId, e.getMessage());
        }
    }

    /** Instant deterministic title for the SSE response; the LLM title replaces it shortly after. */
    public String fallbackTitle(String sessionId) {
        try {
            List<String> firsts = jdbc.query(
                    "SELECT content FROM chat_message WHERE session_id=? AND role='user' ORDER BY id LIMIT 1",
                    (rs, n) -> rs.getString(1), sessionId);
            if (firsts.isEmpty()) return "";
            String line = firsts.get(0).replaceAll("\\s+", " ").trim();
            return line.length() <= 24 ? line : line.substring(0, 24) + "…";
        } catch (Exception e) {
            return "";
        }
    }

    /** One auxiliary LLM call; mirrors DSH's first-prompt-llm policy (one line, language of the message, short). */
    private String generate(String firstPrompt) throws Exception {
        String system = """
                Create a concise title for an AI assistant session from the supplied human message.
                Return only the title on one line, in plain text of natural language, with no quotes, \
                prefix, explanation, Markdown, XML, or terminal control codes. No code is allowed.
                Use the language of the message.
                Aim for about 6 words in non-CJK languages or 12 CJK characters.
                If the message gives little to name, still return a short best-effort title \
                instead of explaining.
                """;
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParams(Map.of(
                        "chat_template_kwargs", Map.of("enable_thinking", false)))
                .maxTokens(64)
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                Msg.builder().role(MsgRole.USER)
                        .textContent("Generate the session title from this human message:\n" + firstPrompt)
                        .build());
        StringBuilder text = new StringBuilder();
        // Model interface is streaming-only; block until the short reply completes.
        for (ChatResponse resp : model.stream(messages, List.of(), options).toIterable()) {
            if (resp.getContent() == null) continue;
            for (ContentBlock block : resp.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    text.append(tb.getText());
                }
            }
        }
        // First non-empty line, stripped of whole-line *emphasis* (mirrors DSH titleFromOutput).
        String line = java.util.Arrays.stream(text.toString().split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst().orElse("");
        if (line.matches("^\\*{1,3}[^*].*\\*{1,3}$")) {
            line = line.replaceAll("^\\*{1,3}", "").replaceAll("\\*{1,3}$", "");
        }
        line = line.replaceAll("^[#>\\-\\s]+", "").trim();
        if (line.isEmpty()) {
            // Deterministic fallback: truncate the first prompt.
            line = firstPrompt.replaceAll("\\s+", " ").trim();
            line = line.length() <= 24 ? line : line.substring(0, 24) + "…";
        }
        return line.length() <= 40 ? line : line.substring(0, 40) + "…";
    }
}
