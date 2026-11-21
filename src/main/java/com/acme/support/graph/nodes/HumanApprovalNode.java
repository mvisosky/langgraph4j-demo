package com.acme.support.graph.nodes;

import com.acme.support.ai.RefundAssistants;
import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * <b>Human in the loop.</b> The graph is compiled with {@code interruptBefore(HUMAN_APPROVAL)}, so execution
 * stops <i>before</i> this node and the checkpoint is persisted in Postgres. Nothing waits in memory: the
 * service can restart, and hours later a specialist calls {@code POST /api/conversations/{id}/approval},
 * which resumes the graph from the checkpoint with {@code approval_decision} merged into the state.
 * This node then applies the human decision.
 */
@Component
public class HumanApprovalNode implements NodeActionWithConfig<SupportState> {

    private final RefundAssistants.RefundResponder responder;
    private final SupportRepository repository;
    private final AgentMetrics metrics;

    public HumanApprovalNode(RefundAssistants.RefundResponder responder, SupportRepository repository, AgentMetrics metrics) {
        this.responder = responder;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String decision = state.str(APPROVAL_DECISION).orElse("REJECTED").toUpperCase(Locale.ROOT);
        String approver = state.str(APPROVER).orElse("unknown approver");
        String note = state.str(APPROVAL_NOTE).orElse("");

        if ("APPROVED".equals(decision)) {
            metrics.humanApproval("approved");
            return update("human_approval: APPROVED by " + approver, APPROVAL_DECISION, "APPROVED");
        }

        metrics.humanApproval("rejected");
        String orderId = state.str(REFUND_ORDER_ID).orElse("?");
        BigDecimal amount = BigDecimal.valueOf(state.decimal(REFUND_AMOUNT).orElse(0.0));
        repository.recordRefund("%s:%d:%s".formatted(config.threadId().orElse("default"), state.integer(TURN), orderId),
                orderId, state.customerId(), amount, state.str(REFUND_REASON).orElse(""),
                "REJECTED", approver, config.threadId().orElse(null));

        String facts = "A refund specialist reviewed the $%s refund for order %s and declined it. Reviewer note: %s"
                .formatted(amount, orderId, note.isBlank() ? "(none)" : note);
        var update = new HashMap<String, Object>(update("human_approval: REJECTED by " + approver,
                APPROVAL_DECISION, "REJECTED",
                EVIDENCE, state.str(EVIDENCE).orElse("") + "\n" + facts));
        update.put(DRAFT, responder.respond(state.str(CUSTOMER_PROFILE).orElse(""), state.lastUserText(), facts));
        return update;
    }
}
