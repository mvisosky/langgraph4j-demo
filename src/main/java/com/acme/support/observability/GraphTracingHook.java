package com.acme.support.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LangGraph4j "wrap call" node hook that turns every node execution into an OpenTelemetry span
 * (child of whatever span is current - typically the HTTP request span created by Spring) and a
 * Micrometer timer ({@code agent.graph.node}).
 * <p>
 * Because the span is made current while the node runs, every LLM call made by the node
 * (see {@link LlmTelemetryListener}) and every JDBC call nests underneath it, so a single trace in
 * Jaeger shows: HTTP request → graph nodes → LLM calls / tool calls / SQL.
 * <p>
 * We use a custom hook instead of {@code langgraph4j-opentelemetry}'s {@code OTELWrapCallTraceHook}
 * because that one resolves the SDK through {@code GlobalOpenTelemetry} (Spring Boot does not
 * register a global instance) and names every span "evaluateNode".
 */
public class GraphTracingHook<S extends AgentState> implements NodeHook.WrapCall<S> {

    private final String graphName;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;

    public GraphTracingHook(String graphName, Tracer tracer, MeterRegistry meterRegistry) {
        this.graphName = graphName;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CompletableFuture<Map<String, Object>> applyWrap(String nodeId,
                                                            S state,
                                                            RunnableConfig config,
                                                            AsyncNodeActionWithConfig<S> action) {
        final Span span = tracer.spanBuilder("%s.%s".formatted(graphName, nodeId))
                .setParent(Context.current())
                .setAttribute("lg4j.graph", graphName)
                .setAttribute("lg4j.node.id", nodeId)
                .setAttribute("lg4j.thread.id", config.threadId().orElse("default"))
                .setAttribute("lg4j.studio", config.isRunningInStudio())
                .startSpan();
        state.<String>value("customer_id").ifPresent(id -> span.setAttribute("customer.id", id));

        final Timer.Sample sample = Timer.start(meterRegistry);

        CompletableFuture<Map<String, Object>> result;
        try (Scope ignored = span.makeCurrent()) {
            result = action.apply(state, config);
        } catch (Throwable t) {
            result = CompletableFuture.failedFuture(t);
        }

        return result.whenComplete((update, error) -> {
            if (error != null) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
            } else if (update != null) {
                span.setAttribute("lg4j.node.updated_keys", String.join(",", update.keySet()));
                span.setStatus(StatusCode.OK);
            }
            span.end();
            sample.stop(Timer.builder("agent.graph.node")
                    .description("Execution time of a LangGraph4j node")
                    .tag("graph", graphName)
                    .tag("node", nodeId)
                    .tag("outcome", error == null ? "success" : "error")
                    .register(meterRegistry));
        });
    }
}
