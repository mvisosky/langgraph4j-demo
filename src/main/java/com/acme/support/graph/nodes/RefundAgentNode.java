package com.acme.support.graph.nodes;

import com.acme.support.ai.RefundAssistants;
import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.RefundPolicyService;
import com.acme.support.domain.RefundPolicyService.Outcome;
import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.acme.support.graph.SupportState.*;

/**
 * Refund specialist. Three steps, deliberately split between LLM and code:
 * <ol>
 *   <li><b>LLM extraction</b> (structured output): which order / item, why, damaged or not</li>
 *   <li><b>policy-as-code</b> decides eligibility, amount and whether a human must approve</li>
 *   <li>the graph routes on the decision: auto-approve → issue_refund; over the limit → human_approval
 *       (the graph <i>pauses</i> there); ineligible / missing info → explain and go to the critic</li>
 * </ol>
 */
@Component
public class RefundAgentNode implements NodeActionWithConfig<SupportState> {

    private final RefundAssistants.RefundRequestExtractor extractor;
    private final RefundAssistants.RefundResponder responder;
    private final RefundPolicyService policy;
    private final SupportRepository repository;
    private final AgentMetrics metrics;

    public RefundAgentNode(RefundAssistants.RefundRequestExtractor extractor,
                           RefundAssistants.RefundResponder responder,
                           RefundPolicyService policy,
                           SupportRepository repository,
                           AgentMetrics metrics) {
        this.extractor = extractor;
        this.responder = responder;
        this.policy = policy;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String customerId = state.customerId();
        var customer = repository.customer(customerId)
                .orElseThrow(() -> new IllegalStateException("Unknown customer " + customerId));
        var orders = repository.orders(customerId);
        String orderList = orders.isEmpty() ? "(none)" : orders.stream().map(Order::oneLine).collect(Collectors.joining("\n"));

        var request = extractor.extract(orderList, state.recentTranscript(6));
        var order = repository.order(customerId, request.orderId());
        BigDecimal refunded = order.map(o -> repository.refundedSoFar(o.id())).orElse(BigDecimal.ZERO);
        var decision = policy.evaluate(customer, order, request.sku(), request.damagedOrWrong(), refunded);
        metrics.refund(decision.outcome().name());

        String evidence = "Customer orders:\n%s\nRefund request: order=%s sku=%s reason=%s damaged=%s\nPolicy decision: %s, amount $%s. %s"
                .formatted(orderList, request.orderId(), request.sku(), request.reason(), request.damagedOrWrong(),
                        decision.outcome(), decision.amount(), decision.note());

        var update = new HashMap<String, Object>(update(
                "refund_agent: %s %s $%s".formatted(decision.outcome(), request.orderId(), decision.amount()),
                REFUND_ORDER_ID, order.map(Order::id).orElse(request.orderId()),
                REFUND_AMOUNT, decision.amount().doubleValue(),
                REFUND_REASON, request.reason(),
                REFUND_DECISION, decision.outcome().name(),
                REFUND_POLICY_NOTE, decision.note(),
                EVIDENCE, evidence));

        if (decision.outcome() == Outcome.NEEDS_APPROVAL) {
            metrics.humanApproval("requested");
        }
        if (decision.outcome() == Outcome.INELIGIBLE || decision.outcome() == Outcome.NEEDS_INFO) {
            update.put(DRAFT, responder.respond(customer.promptProfile(), state.lastUserText(),
                    "%s: %s".formatted(decision.outcome(), decision.note())));
        }
        return update;
    }
}
