package com.acme.support.config;

import com.acme.support.observability.LlmTelemetryListener;
import com.acme.support.observability.ObservabilityConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * Model routing: two tiers of chat model.
 * <ul>
 *   <li><b>smart</b> (Claude Sonnet 5.5) – ReAct order agent, answer generation, refund reasoning, the critic.</li>
 *   <li><b>fast</b> (Claude Haiku 5.5) – guardrail classification, intent routing, document grading,
 *       query rewriting, memory extraction, summarisation.</li>
 * </ul>
 * Embeddings run in-process (all-MiniLM-L6-v2, 384 dims) because Anthropic has no embeddings API; this also
 * keeps RAG and memory working with no extra service. Changing the embedding model means re-ingesting.
 */
@Configuration(proxyBeanMethods = false)
public class AiModelConfig {

    public static final int EMBEDDING_DIMENSION = 384;

    @Bean
    @Qualifier("smart")
    ChatModel smartChatModel(AgentProperties props, OpenTelemetry otel, MeterRegistry meters) {
        return chatModel(props, true, new LlmTelemetryListener(ObservabilityConfig.agentTracer(otel), meters, "smart", captureContent(props)));
    }

    @Bean
    @Qualifier("fast")
    ChatModel fastChatModel(AgentProperties props, OpenTelemetry otel, MeterRegistry meters) {
        return chatModel(props, false, new LlmTelemetryListener(ObservabilityConfig.agentTracer(otel), meters, "fast", captureContent(props)));
    }

    @Bean
    EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    private static boolean captureContent(AgentProperties props) {
        return props.observability() != null && props.observability().captureContent();
    }

    private static ChatModel chatModel(AgentProperties props, boolean smart, LlmTelemetryListener listener) {
        return switch (props.provider()) {
            case "anthropic" -> anthropic(props.anthropic(), smart, listener);
            case "ollama" -> ollama(props.ollama(), smart, listener);
            default -> throw new IllegalStateException("Unsupported agent.provider: " + props.provider());
        };
    }

    private static ChatModel anthropic(AgentProperties.Anthropic cfg, boolean smart, LlmTelemetryListener listener) {
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Export it, or run with SPRING_PROFILES_ACTIVE=ollama for offline mode.");
        }
        return AnthropicChatModel.builder()
                .apiKey(cfg.apiKey())
                .modelName(smart ? cfg.smartModel() : cfg.fastModel())
                .maxTokens(cfg.maxTokens())
                // Prompt caching: the long, stable system prompts (policies, tool definitions) are cached
                // server-side, cutting latency and input-token cost on every subsequent turn.
                .cacheSystemMessages(cfg.promptCaching())
                .cacheTools(cfg.promptCaching())
                // Native structured outputs: AiServices returning records/enums send a JSON schema
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(2)
                .listeners(List.of(listener))
                .build();
    }

    private static ChatModel ollama(AgentProperties.Ollama cfg, boolean smart, LlmTelemetryListener listener) {
        return OllamaChatModel.builder()
                .baseUrl(cfg.baseUrl())
                .modelName(smart ? cfg.smartModel() : cfg.fastModel())
                .temperature(smart ? 0.2 : 0.0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .timeout(Duration.ofMinutes(3))
                .listeners(List.of(listener))
                .build();
    }
}
