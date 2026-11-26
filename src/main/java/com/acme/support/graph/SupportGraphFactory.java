package com.acme.support.graph;

import com.acme.support.config.AgentProperties;
import com.acme.support.graph.nodes.*;
import com.acme.support.observability.GraphTracingHook;
import com.acme.support.observability.ObservabilityConfig;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.service.output.OutputParsingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.hook.RetryPolicy;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

import static com.acme.support.graph.Nodes.*;
import static com.acme.support.graph.SupportState.*;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * Builds the support agent graph.
 *
 * <pre>
 * START → start_turn → input_guardrail ─┬─ BLOCK → blocked_response → END
 *                                       ├─ ANONYMOUS → identify_customer (email + one-time code) → END
 *                                       └─ ALLOW → load_context ═╦═ load_customer ═╗   (parallel fan-out / fan-in)
 *                                                                 ╚═ recall_memory ═╩═ supervisor
 * supervisor ─┬─ KNOWLEDGE    → knowledge_agent (corrective-RAG sub-graph) ─────────────┐
 *             ├─ ORDER_STATUS → order_agent (nested ReAct AgentExecutor + tools) ───────┤
 *             ├─ REFUND       → refund_agent ─┬─ AUTO_APPROVE ───────→ issue_refund ───┤
 *             │                               ├─ NEEDS_APPROVAL → ⏸ human_approval ─┬─┘ (APPROVED → issue_refund)
 *             │                               └─ INELIGIBLE/NEEDS_INFO ─────────────┤   (REJECTED → critic)
 *             ├─ ESCALATE     → escalation_agent (ticket) ──────────────────────────────┤
 *             └─ SMALLTALK    → smalltalk_agent ──────────────┐                         ▼
 *                                                             │      critic ⇄ reviser (reflection loop)
 *                                                             ▼        │ ACCEPT
 *                                                   output_guardrail ◄─┘ → memory_writer → END
 * </pre>
 */
@Component
public class SupportGraphFactory {

    public static final String GRAPH_NAME = "support";

    private final StateSerializer<SupportState> serializer;
    private final KnowledgeRagSubgraph knowledgeRag;
    private final InputGuardrailNode inputGuardrail;
    private final BlockedResponseNode blockedResponse;
    private final IdentifyCustomerNode identifyCustomer;
    private final CustomerContextNode customerContext;
    private final RecallMemoryNode recallMemory;
    private final SupervisorNode supervisor;
    private final OrderAgentNode orderAgent;
    private final RefundAgentNode refundAgent;
    private final HumanApprovalNode humanApproval;
    private final IssueRefundNode issueRefund;
    private final EscalationNode escalation;
    private final SmallTalkNode smallTalk;
    private final CriticNode critic;
    private final ReviserNode reviser;
    private final OutputGuardrailNode outputGuardrail;
    private final MemoryWriterNode memoryWriter;
    private final NodeHook.WrapCall<SupportState> tracingHook;
    private final int maxRevisions;

    public SupportGraphFactory(StateSerializer<SupportState> serializer,
                               KnowledgeRagSubgraph knowledgeRag,
                               InputGuardrailNode inputGuardrail,
                               BlockedResponseNode blockedResponse,
                               IdentifyCustomerNode identifyCustomer,
                               CustomerContextNode customerContext,
                               RecallMemoryNode recallMemory,
                               SupervisorNode supervisor,
                               OrderAgentNode orderAgent,
                               RefundAgentNode refundAgent,
                               HumanApprovalNode humanApproval,
                               IssueRefundNode issueRefund,
                               EscalationNode escalation,
                               SmallTalkNode smallTalk,
                               CriticNode critic,
                               ReviserNode reviser,
                               OutputGuardrailNode outputGuardrail,
                               MemoryWriterNode memoryWriter,
                               OpenTelemetry openTelemetry,
                               MeterRegistry meterRegistry,
                               AgentProperties props) {
        this.serializer = serializer;
        this.knowledgeRag = knowledgeRag;
        this.inputGuardrail = inputGuardrail;
        this.blockedResponse = blockedResponse;
        this.identifyCustomer = identifyCustomer;
        this.customerContext = customerContext;
        this.recallMemory = recallMemory;
        this.supervisor = supervisor;
        this.orderAgent = orderAgent;
        this.refundAgent = refundAgent;
        this.humanApproval = humanApproval;
        this.issueRefund = issueRefund;
        this.escalation = escalation;
        this.smallTalk = smallTalk;
        this.critic = critic;
        this.reviser = reviser;
        this.outputGuardrail = outputGuardrail;
        this.memoryWriter = memoryWriter;
        this.tracingHook = new GraphTracingHook<>(GRAPH_NAME, ObservabilityConfig.agentTracer(openTelemetry), meterRegistry);
        this.maxRevisions = props.reflection().maxRevisions();
    }

    public StateGraph<SupportState> build() throws GraphStateException {

        // Transient LLM failures (rate limits, timeouts, malformed structured output) are retried per node with
        // exponential back-off. Node-specific wrap hooks run outside the global tracing hook, so every attempt
        // shows up as its own span.
        NodeHook.WrapCall<SupportState> retry = RetryPolicy.builder()
                .maxAttempts(3)
                .retryDelay(Duration.ofMillis(750))
                .backoffFactor(2.0)
                .jitter(true)
                .retryOn(e -> e instanceof RetriableException || e instanceof OutputParsingException)
                .build()
                .asHook();

        var graph = new StateGraph<>(SupportState.SCHEMA, serializer)
                .addWrapCallNodeHook(tracingHook);
        for (String node : List.of(ORDER_AGENT, REFUND_AGENT, HUMAN_APPROVAL, ISSUE_REFUND,
                ESCALATION_AGENT, SMALLTALK_AGENT, REVISER)) {
            graph.addWrapCallNodeHook(node, retry);
        }

        return graph
                // ------------------------------------------------------------------ nodes
                .addNode(START_TURN, node_async((state, config) -> {
                    var update = SupportState.resetTurn();         // clear the previous turn's scratch keys
                    update.put(TURN, state.integer(TURN) + 1);
                    return update;
                }))
                .addNode(INPUT_GUARDRAIL, node_async(inputGuardrail))
                .addNode(BLOCKED_RESPONSE, node_async(blockedResponse))
                .addNode(IDENTIFY_CUSTOMER, node_async(identifyCustomer))
                .addNode(LOAD_CONTEXT, node_async((state, config) ->
                        update("load_context: fan-out (customer profile ∥ memory recall)")))
                .addNode(LOAD_CUSTOMER, parallelBranch(LOAD_CUSTOMER, customerContext))
                .addNode(RECALL_MEMORY, parallelBranch(RECALL_MEMORY, recallMemory))
                .addNode(SUPERVISOR, node_async(supervisor))
                .addNode(KNOWLEDGE_AGENT, knowledgeRag.build(serializer))
                .addNode(ORDER_AGENT, node_async(orderAgent))
                .addNode(REFUND_AGENT, node_async(refundAgent))
                .addNode(HUMAN_APPROVAL, node_async(humanApproval))
                .addNode(ISSUE_REFUND, node_async(issueRefund))
                .addNode(ESCALATION_AGENT, node_async(escalation))
                .addNode(SMALLTALK_AGENT, node_async(smallTalk))
                .addNode(CRITIC, node_async(critic))
                .addNode(REVISER, node_async(reviser))
                .addNode(OUTPUT_GUARDRAIL, node_async(outputGuardrail))
                .addNode(MEMORY_WRITER, node_async(memoryWriter))

                // ------------------------------------------------------------------ edges
                .addEdge(START, START_TURN)
                .addEdge(START_TURN, INPUT_GUARDRAIL)
                .addConditionalEdges(INPUT_GUARDRAIL,
                        edge_async(state -> {
                            if ("BLOCK".equals(state.str(GUARDRAIL_VERDICT).orElse("ALLOW"))) {
                                return "BLOCK";
                            }
                            // no verified customer yet: only the identity check may run
                            return state.value(CUSTOMER_ID).isEmpty() ? "ANONYMOUS" : "ALLOW";
                        }),
                        EdgeMappings.builder()
                                .to(BLOCKED_RESPONSE, "BLOCK")
                                .to(IDENTIFY_CUSTOMER, "ANONYMOUS")
                                .to(LOAD_CONTEXT, "ALLOW")
                                .build())
                .addEdge(BLOCKED_RESPONSE, END)
                .addEdge(IDENTIFY_CUSTOMER, END)

                // parallel fan-out / fan-in (executor supplied per run via RunnableConfig.addParallelNodeExecutor)
                .addEdge(LOAD_CONTEXT, LOAD_CUSTOMER)
                .addEdge(LOAD_CONTEXT, RECALL_MEMORY)
                .addEdge(LOAD_CUSTOMER, SUPERVISOR)
                .addEdge(RECALL_MEMORY, SUPERVISOR)

                // supervisor → specialists
                .addConditionalEdges(SUPERVISOR,
                        edge_async(state -> state.str(ROUTE).orElse("KNOWLEDGE")),
                        EdgeMappings.builder()
                                .to(KNOWLEDGE_AGENT, "KNOWLEDGE")
                                .to(ORDER_AGENT, "ORDER_STATUS")
                                .to(REFUND_AGENT, "REFUND")
                                .to(ESCALATION_AGENT, "ESCALATE")
                                .to(SMALLTALK_AGENT, "SMALLTALK")
                                .build())
                .addEdge(KNOWLEDGE_AGENT, CRITIC)
                .addEdge(ORDER_AGENT, CRITIC)
                .addEdge(ESCALATION_AGENT, CRITIC)
                .addEdge(SMALLTALK_AGENT, OUTPUT_GUARDRAIL)   // low-risk path skips reflection

                // refund workflow with human-in-the-loop
                .addConditionalEdges(REFUND_AGENT,
                        edge_async(state -> state.str(REFUND_DECISION).orElse("NEEDS_INFO")),
                        EdgeMappings.builder()
                                .to(ISSUE_REFUND, "AUTO_APPROVE")
                                .to(HUMAN_APPROVAL, "NEEDS_APPROVAL")
                                .to(CRITIC, "INELIGIBLE")
                                .to(CRITIC, "NEEDS_INFO")
                                .build())
                .addConditionalEdges(HUMAN_APPROVAL,
                        edge_async(state -> state.str(APPROVAL_DECISION).orElse("REJECTED")),
                        EdgeMappings.builder()
                                .to(ISSUE_REFUND, "APPROVED")
                                .to(CRITIC, "REJECTED")
                                .build())
                .addEdge(ISSUE_REFUND, CRITIC)

                // reflection loop
                .addConditionalEdges(CRITIC,
                        edge_async(state -> "REVISE".equals(state.str(CRITIC_VERDICT).orElse("PASS"))
                                && state.integer(REVISIONS) < maxRevisions ? "REVISE" : "ACCEPT"),
                        EdgeMappings.builder()
                                .to(REVISER, "REVISE")
                                .to(OUTPUT_GUARDRAIL, "ACCEPT")
                                .build())
                .addEdge(REVISER, CRITIC)

                // finish
                .addEdge(OUTPUT_GUARDRAIL, MEMORY_WRITER)
                .addEdge(MEMORY_WRITER, END);
    }

    /**
     * Parallel branches are folded into a single parallel node at compile time, so graph-level hooks see only
     * the fork node. Wrapping each branch explicitly keeps one span/timer per branch in the traces.
     */
    private AsyncNodeActionWithConfig<SupportState> parallelBranch(String nodeId, NodeActionWithConfig<SupportState> action) {
        AsyncNodeActionWithConfig<SupportState> async = node_async(action);
        return (state, config) -> tracingHook.applyWrap(nodeId, state, config, async);
    }
}
