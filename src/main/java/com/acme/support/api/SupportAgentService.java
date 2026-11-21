package com.acme.support.api;

import com.acme.support.api.ApiModels.*;
import com.acme.support.graph.Nodes;
import com.acme.support.graph.SupportState;
import com.acme.support.memory.LongTermMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static com.acme.support.graph.SupportState.*;

/**
 * Application service around the compiled graph: start conversations, run turns (blocking or streamed),
 * resume runs paused for human approval, and inspect checkpointed state and history.
 */
@Service
public class SupportAgentService {

    private static final Logger log = LoggerFactory.getLogger(SupportAgentService.class);

    private final CompiledGraph<SupportState> graph;
    private final JdbcClient jdbc;
    private final ExecutorService graphExecutor;
    private final LongTermMemoryService memory;

    public SupportAgentService(CompiledGraph<SupportState> graph,
                               JdbcClient jdbc,
                               @Qualifier("graphExecutor") ExecutorService graphExecutor,
                               LongTermMemoryService memory) {
        this.graph = graph;
        this.jdbc = jdbc;
        this.graphExecutor = graphExecutor;
        this.memory = memory;
    }

    // ------------------------------------------------------------------ conversations

    public ConversationStarted startConversation(String customerId) {
        boolean exists = jdbc.sql("SELECT count(*) FROM customers WHERE id = :id").param("id", customerId)
                .query(Long.class).single() > 0;
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown customer " + customerId);
        }
        String threadId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO conversations (thread_id, customer_id) VALUES (:t, :c)")
                .param("t", threadId).param("c", customerId).update();
        return new ConversationStarted(threadId, customerId);
    }

    public TurnResult sendMessage(String threadId, String message) {
        var config = runConfig(threadId);
        graph.invoke(turnInput(threadId, message), config);
        return result(threadId, config);
    }

    /** Streams one event per executed node (Server-Sent Events), then the final result. */
    public SseEmitter streamMessage(String threadId, String message) {
        var input = turnInput(threadId, message);   // validate before going async
        var emitter = new SseEmitter(300_000L);
        graphExecutor.submit(Context.current().wrap(() -> {
            try {
                var config = runConfig(threadId);
                graph.stream(input, config).stream().forEach(output -> {
                    var trace = output.state().strings(AGENT_TRACE);
                    try {
                        emitter.send(SseEmitter.event().name("node")
                                .data(new NodeEvent(output.node(), trace.isEmpty() ? "" : trace.getLast())));
                    } catch (Exception e) {
                        throw new IllegalStateException("client disconnected", e);
                    }
                });
                emitter.send(SseEmitter.event().name("result").data(result(threadId, config)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Streaming run failed for thread {}: {}", threadId, e.toString());
                emitter.completeWithError(e);
            }
        }));
        return emitter;
    }

    // ------------------------------------------------------------------ human in the loop

    public TurnResult resolveApproval(String threadId, ApprovalRequest request) {
        ownerOf(threadId);
        var config = runConfig(threadId);
        if (!awaitingApproval(config)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation is not awaiting approval");
        }
        // Resume from the persisted checkpoint, merging the human decision into the state
        graph.invoke(GraphInput.resume(Map.of(
                APPROVAL_DECISION, request.approved() ? "APPROVED" : "REJECTED",
                APPROVER, request.approver(),
                APPROVAL_NOTE, request.note() == null ? "" : request.note())), config);
        jdbc.sql("DELETE FROM pending_approvals WHERE thread_id = :t").param("t", threadId).update();
        return result(threadId, config);
    }

    public List<PendingApproval> pendingApprovals() {
        return jdbc.sql("SELECT * FROM pending_approvals ORDER BY requested_at")
                .query((rs, n) -> new PendingApproval(rs.getString("thread_id"), rs.getString("customer_id"),
                        rs.getString("order_id"), rs.getBigDecimal("amount"), rs.getString("reason"),
                        rs.getString("policy_note"), rs.getObject("requested_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ inspection

    public ConversationView conversation(String threadId) {
        String customerId = ownerOf(threadId);
        var config = runConfig(threadId);
        var snapshot = graph.stateOf(config);
        if (snapshot.isEmpty()) {
            return new ConversationView(threadId, customerId, 0, false, null, List.of());
        }
        var state = snapshot.get().state();
        var messages = state.messages().stream()
                .map(m -> switch (m) {
                    case UserMessage um -> new Message("customer", um.hasSingleText() ? um.singleText() : "[non-text]");
                    case AiMessage ai -> new Message("agent", ai.text());
                    default -> new Message(m.type().name().toLowerCase(), "");
                })
                .toList();
        return new ConversationView(threadId, customerId, state.integer(TURN), awaitingApproval(config),
                state.str(CONVERSATION_SUMMARY).orElse(null), messages);
    }

    /** Every checkpoint of the thread (newest first) - the basis for time travel / replay. */
    public List<CheckpointView> history(String threadId) {
        ownerOf(threadId);
        return graph.getStateHistory(runConfig(threadId)).stream()
                .map(s -> new CheckpointView(s.checkpointId(), s.node(), s.nextNodeId(), s.savedAt()))
                .toList();
    }

    public String mermaid() {
        return graph.getGraph(GraphRepresentation.Type.MERMAID, "Acme support agent", true).content();
    }

    public void forgetCustomer(String customerId) {
        memory.forget(customerId);
    }

    // ------------------------------------------------------------------ internals

    private GraphInput turnInput(String threadId, String message) {
        String customerId = ownerOf(threadId);
        if (awaitingApproval(runConfig(threadId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A refund on this conversation is awaiting specialist approval; please wait for the decision.");
        }
        return GraphInput.args(Map.of(
                MESSAGES, List.of(UserMessage.from(message)),
                CUSTOMER_ID, customerId));
    }

    /**
     * Per-run configuration: the thread id selects the checkpoint thread (= the conversation); executors are
     * wrapped so the OpenTelemetry context follows the graph onto its worker threads and parallel branches.
     */
    private RunnableConfig runConfig(String threadId) {
        var executor = Context.taskWrapping(graphExecutor);
        return RunnableConfig.builder()
                .threadId(threadId)
                .emitterExecutor(executor)
                .addParallelNodeExecutor(Nodes.LOAD_CONTEXT, executor)
                .build();
    }

    private boolean awaitingApproval(RunnableConfig config) {
        return graph.stateOf(config)
                .map(s -> Nodes.HUMAN_APPROVAL.equals(s.nextNodeId()))
                .orElse(false);
    }

    private String ownerOf(String threadId) {
        return jdbc.sql("SELECT customer_id FROM conversations WHERE thread_id = :t").param("t", threadId)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation " + threadId));
    }

    private TurnResult result(String threadId, RunnableConfig config) {
        StateSnapshot<SupportState> snapshot = graph.stateOf(config)
                .orElseThrow(() -> new IllegalStateException("No checkpoint for thread " + threadId));
        SupportState state = snapshot.state();
        boolean awaiting = Nodes.HUMAN_APPROVAL.equals(snapshot.nextNodeId());

        String answer;
        if (awaiting) {
            jdbc.sql("""
                            INSERT INTO pending_approvals (thread_id, customer_id, order_id, amount, reason, policy_note)
                            VALUES (:t, :c, :o, :a, :r, :p) ON CONFLICT (thread_id) DO NOTHING""")
                    .param("t", threadId).param("c", state.customerId())
                    .param("o", state.str(REFUND_ORDER_ID).orElse(null))
                    .param("a", BigDecimal.valueOf(state.decimal(REFUND_AMOUNT).orElse(0.0)))
                    .param("r", state.str(REFUND_REASON).orElse(null))
                    .param("p", state.str(REFUND_POLICY_NOTE).orElse(null))
                    .update();
            answer = "Thanks - your refund of $%.2f for order %s qualifies, and because it's over our automatic limit a "
                    .formatted(state.decimal(REFUND_AMOUNT).orElse(0.0), state.str(REFUND_ORDER_ID).orElse("?"))
                    + "specialist will confirm it shortly. You'll get an update in this conversation.";
        } else {
            answer = state.str(FINAL_ANSWER).orElse("");
        }

        var refund = state.str(REFUND_DECISION)
                .map(d -> new RefundInfo(state.str(REFUND_ORDER_ID).orElse(null), state.decimal(REFUND_AMOUNT).orElse(null),
                        d, state.str(REFUND_POLICY_NOTE).orElse(null), state.str(APPROVAL_DECISION).orElse(null)))
                .orElse(null);

        return new TurnResult(threadId,
                awaiting ? TurnStatus.AWAITING_APPROVAL : TurnStatus.COMPLETED,
                answer,
                state.str(ROUTE).orElse(null),
                refund,
                state.str(TICKET_ID).orElse(null),
                state.integer(REVISIONS),
                state.strings(AGENT_TRACE),
                Span.current().getSpanContext().getTraceId(),
                snapshot.checkpointId());
    }
}
