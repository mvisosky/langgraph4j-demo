package com.acme.support.graph.nodes;

import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.GraphTracingHook;
import com.acme.support.observability.ObservabilityConfig;
import com.acme.support.tools.OrderTools;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.acme.support.graph.SupportState.*;

/**
 * Order specialist: a <b>nested ReAct agent</b> built with LangGraph4j's prebuilt {@link AgentExecutor}
 * (call model → execute tools → call model … until the model answers).
 * <p>
 * It runs as its own small graph with its own state type, invoked from this node of the parent graph: the
 * "agent as a node" pattern. Its tools are instantiated per request and bound to the current customer.
 */
@Component
public class OrderAgentNode implements NodeActionWithConfig<SupportState> {

    private static final String SYSTEM_PROMPT = """
            You are the order specialist at Acme Outfitters. Use the tools to look up the customer's orders,
            order details and shipment tracking - never guess statuses or dates. You can only see this
            customer's orders. Answer in under 120 words, mention order ids, and if something looks wrong
            (e.g. tracking stalled for 5+ business days) say that you can escalate to a specialist.
            Today's date matters for "when will it arrive" questions; tracking events include dates.
            """;

    private final ChatModel smartModel;
    private final SupportRepository repository;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final Executor graphExecutor;

    public OrderAgentNode(@Qualifier("smart") ChatModel smartModel,
                          SupportRepository repository,
                          OpenTelemetry openTelemetry,
                          MeterRegistry meters,
                          @Qualifier("graphExecutor") Executor graphExecutor) {
        this.smartModel = smartModel;
        this.repository = repository;
        this.tracer = ObservabilityConfig.agentTracer(openTelemetry);
        this.meters = meters;
        this.graphExecutor = graphExecutor;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) throws Exception {
        var tools = new OrderTools(repository, state.customerId(), tracer);

        var agent = AgentExecutor.builder()
                .chatModel(smartModel)
                .systemMessage(SystemMessage.from(SYSTEM_PROMPT))
                .toolsFromObject(tools)
                .build()
                .addWrapCallNodeHook(new GraphTracingHook<>("order_agent", tracer, meters))
                .compile(CompileConfig.builder().recursionLimit(12).build());

        List<ChatMessage> input = List.of(UserMessage.from("""
                Customer: %s
                Conversation so far:
                %s

                Current request: %s""".formatted(
                state.str(CUSTOMER_PROFILE).orElse("unknown"),
                state.recentTranscript(6),
                state.lastUserText())));

        // The nested graph runs on its own threads: wrap the executor so the current OpenTelemetry context
        // (this node's span) is propagated and the agent's LLM/tool spans nest underneath it.
        var runConfig = RunnableConfig.builder()
                .emitterExecutor(Context.taskWrapping(graphExecutor))
                .build();
        var result = agent.invoke(GraphInput.args(Map.of(AgentExecutor.State.MESSAGES, input)), runConfig)
                .orElseThrow(() -> new IllegalStateException("order agent produced no state"));

        String answer = result.finalResponse()
                .or(() -> result.lastMessage()
                        .filter(m -> m instanceof AiMessage)
                        .map(m -> ((AiMessage) m).text()))
                .orElse("I wasn't able to look that up right now. Could you share your order id?");

        List<ToolExecutionResultMessage> toolResults = result.messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> (ToolExecutionResultMessage) m)
                .toList();
        String evidence = toolResults.isEmpty() ? "No tool calls were made."
                : toolResults.stream().map(t -> "%s → %s".formatted(t.toolName(), t.text())).collect(Collectors.joining("\n"));
        // the critic sees only the evidence: include the profile the agent was given (name, tier), or it flags
        // a correctly personalised reply ("Hi Alice", "as a Gold member") as unsupported
        evidence += "\nCustomer profile (from our records): " + state.str(CUSTOMER_PROFILE).orElse("unknown");

        return update("order_agent: %d tool call(s): %s".formatted(toolResults.size(),
                        toolResults.stream().map(ToolExecutionResultMessage::toolName).distinct().toList()),
                DRAFT, answer, EVIDENCE, evidence);
    }
}
