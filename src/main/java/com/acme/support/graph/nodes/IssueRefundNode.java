package com.acme.support.graph.nodes;

import com.acme.support.ai.RefundAssistants;
import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * Side-effecting node: records the refund. The write is <b>idempotent</b> (key = thread + turn + order) so
 * a node retry, a replay from a checkpoint, or a duplicated approval call can never pay out twice.
 */
@Component
public class IssueRefundNode implements NodeActionWithConfig<SupportState> {

    private final SupportRepository repository;
    private final RefundAssistants.RefundResponder responder;
    private final AgentMetrics metrics;

    public IssueRefundNode(SupportRepository repository, RefundAssistants.RefundResponder responder, AgentMetrics metrics) {
        this.repository = repository;
        this.responder = responder;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String threadId = config.threadId().orElse("default");
        String orderId = state.str(REFUND_ORDER_ID).orElseThrow(() -> new IllegalStateException("refund order missing"));
        BigDecimal amount = BigDecimal.valueOf(state.decimal(REFUND_AMOUNT).orElse(0.0)).setScale(2, RoundingMode.HALF_UP);
        String decidedBy = "APPROVED".equals(state.str(APPROVAL_DECISION).orElse(""))
                ? state.str(APPROVER).orElse("specialist")
                : "auto-policy";

        long refundId = repository.recordRefund("%s:%d:%s".formatted(threadId, state.integer(TURN), orderId),
                orderId, state.customerId(), amount, state.str(REFUND_REASON).orElse(""), "ISSUED", decidedBy, threadId);
        metrics.refund("ISSUED");

        String facts = "Refund R-%d ISSUED: $%s for order %s, approved by %s. It reaches the original payment method "
                .formatted(refundId, amount, orderId, decidedBy)
                + "in 5-10 business days. Policy note: " + state.str(REFUND_POLICY_NOTE).orElse("");
        String draft = responder.respond(state.str(CUSTOMER_PROFILE).orElse(""), state.lastUserText(), facts);

        return update("issue_refund: R-%d $%s (%s)".formatted(refundId, amount, decidedBy),
                DRAFT, draft,
                EVIDENCE, state.str(EVIDENCE).orElse("") + "\n" + facts);
    }
}
