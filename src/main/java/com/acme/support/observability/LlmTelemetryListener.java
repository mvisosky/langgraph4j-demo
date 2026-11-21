package com.acme.support.observability;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * LangChain4j {@link ChatModelListener} that emits one CLIENT span per LLM call plus token/latency metrics,
 * following the OpenTelemetry GenAI semantic conventions ({@code gen_ai.*}).
 * <p>
 * The span is parented to the current context, i.e. the graph node span opened by {@link GraphTracingHook}.
 */
public class LlmTelemetryListener implements ChatModelListener {

    private static final String SPAN = "otel.span";
    private static final String START = "otel.start";
    private static final String PROVIDER = "otel.provider";

    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final String modelRole;        // "smart" or "fast" - which tier of the model routing made the call
    private final boolean captureContent;  // demo only: record a truncated prompt/response as span events

    public LlmTelemetryListener(Tracer tracer, MeterRegistry meterRegistry, String modelRole, boolean captureContent) {
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.modelRole = modelRole;
        this.captureContent = captureContent;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ChatRequest request = ctx.chatRequest();
        String model = Optional.ofNullable(request.modelName()).orElse("unknown");
        String provider = ctx.modelProvider() == null ? "unknown" : ctx.modelProvider().name().toLowerCase();

        var builder = tracer.spanBuilder("chat " + model)
                .setParent(Context.current())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.provider.name", provider)
                .setAttribute("gen_ai.request.model", model)
                .setAttribute("agent.model.role", modelRole)
                .setAttribute("gen_ai.request.message_count", (long) request.messages().size());
        if (request.temperature() != null) {
            builder.setAttribute("gen_ai.request.temperature", request.temperature());
        }
        if (request.maxOutputTokens() != null) {
            builder.setAttribute("gen_ai.request.max_tokens", (long) request.maxOutputTokens());
        }
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            builder.setAttribute(AttributeKey.stringArrayKey("gen_ai.request.tools"),
                    request.toolSpecifications().stream().map(t -> t.name()).toList());
        }
        Span span = builder.startSpan();

        if (captureContent) {
            lastUserText(request.messages()).ifPresent(text ->
                    span.addEvent("gen_ai.user.message", Attributes.of(
                            AttributeKey.stringKey("gen_ai.content"), truncate(text, 1000))));
        }

        ctx.attributes().put(SPAN, span);
        ctx.attributes().put(START, System.nanoTime());
        ctx.attributes().put(PROVIDER, provider);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN);
        ChatResponse response = ctx.chatResponse();
        String model = Optional.ofNullable(response.modelName())
                .orElseGet(() -> Optional.ofNullable(ctx.chatRequest().modelName()).orElse("unknown"));
        String provider = String.valueOf(ctx.attributes().getOrDefault(PROVIDER, "unknown"));

        TokenUsage usage = response.tokenUsage();
        if (span != null) {
            span.setAttribute("gen_ai.response.model", model);
            if (response.id() != null) {
                span.setAttribute("gen_ai.response.id", response.id());
            }
            if (response.finishReason() != null) {
                span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                        List.of(response.finishReason().name().toLowerCase()));
            }
            if (usage != null) {
                if (usage.inputTokenCount() != null) span.setAttribute("gen_ai.usage.input_tokens", (long) usage.inputTokenCount());
                if (usage.outputTokenCount() != null) span.setAttribute("gen_ai.usage.output_tokens", (long) usage.outputTokenCount());
            }
            if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
                span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.tool_calls"),
                        response.aiMessage().toolExecutionRequests().stream().map(r -> r.name()).toList());
            }
            if (captureContent && response.aiMessage() != null && response.aiMessage().text() != null) {
                span.addEvent("gen_ai.assistant.message", Attributes.of(
                        AttributeKey.stringKey("gen_ai.content"), truncate(response.aiMessage().text(), 1000)));
            }
            span.setStatus(StatusCode.OK);
            span.end();
        }

        if (usage != null) {
            recordTokens("input", usage.inputTokenCount(), model, provider);
            recordTokens("output", usage.outputTokenCount(), model, provider);
        }
        recordDuration(ctx.attributes().get(START), model, provider, "success");
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN);
        if (span != null) {
            span.recordException(ctx.error());
            span.setStatus(StatusCode.ERROR, String.valueOf(ctx.error().getMessage()));
            span.setAttribute("error.type", ctx.error().getClass().getName());
            span.end();
        }
        String model = Optional.ofNullable(ctx.chatRequest().modelName()).orElse("unknown");
        String provider = String.valueOf(ctx.attributes().getOrDefault(PROVIDER, "unknown"));
        recordDuration(ctx.attributes().get(START), model, provider, "error");
    }

    private void recordTokens(String type, Integer count, String model, String provider) {
        if (count == null) return;
        DistributionSummary.builder("gen_ai.client.token.usage")
                .description("Number of tokens used per LLM call")
                .tag("gen_ai.token.type", type)
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.provider.name", provider)
                .tag("agent.model.role", modelRole)
                .register(meterRegistry)
                .record(count);
    }

    private void recordDuration(Object start, String model, String provider, String outcome) {
        if (!(start instanceof Long startNanos)) return;
        Timer.builder("gen_ai.client.operation.duration")
                .description("Latency of LLM calls")
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.provider.name", provider)
                .tag("agent.model.role", modelRole)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static Optional<String> lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um && um.hasSingleText()) {
                return Optional.of(um.singleText());
            }
        }
        return Optional.empty();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
