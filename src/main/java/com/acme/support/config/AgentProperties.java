package com.acme.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Typed configuration for the agent ({@code agent.*} in application.yml).
 */
@ConfigurationProperties("agent")
public record AgentProperties(
        String provider,
        Anthropic anthropic,
        Ollama ollama,
        Refund refund,
        Reflection reflection,
        Rag rag,
        Memory memory,
        Studio studio,
        Observability observability) {

    public record Anthropic(String apiKey, String smartModel, String fastModel, Integer maxTokens, boolean promptCaching) {}

    public record Ollama(String baseUrl, String smartModel, String fastModel) {}

    public record Refund(BigDecimal autoApproveLimit, int windowDays) {}

    public record Reflection(int maxRevisions) {}

    public record Rag(int maxResults, int maxQueryRewrites) {}

    public record Memory(int maxRecalled, double minScore, int summarizeAfterMessages, int keepRecentMessages) {}

    public record Studio(boolean enabled) {}

    /** captureContent: attach truncated prompts/responses to LLM spans (demo only - contains customer data). */
    public record Observability(boolean captureContent) {}
}
