package com.acme.support.graph.nodes;

import com.acme.support.ai.IntentRouter;
import com.acme.support.ai.IntentRouter.Route;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * Supervisor of the multi-agent system: classifies the intent (structured output on the fast model) and the
 * graph's conditional edge dispatches to the matching specialist. Low-confidence decisions and classifier
 * failures fall back to a keyword router so the conversation never dead-ends.
 */
@Component
public class SupervisorNode implements NodeActionWithConfig<SupportState> {

    private static final Logger log = LoggerFactory.getLogger(SupervisorNode.class);
    static final double MIN_CONFIDENCE = 0.4;

    private final IntentRouter router;
    private final AgentMetrics metrics;

    public SupervisorNode(IntentRouter router, AgentMetrics metrics) {
        this.router = router;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String message = state.lastUserText();
        Route route;
        String reason;
        try {
            var decision = router.route(
                    state.str(CUSTOMER_PROFILE).orElse("unknown"),
                    String.join("; ", state.strings(MEMORIES)),
                    state.recentTranscript(8),
                    message);
            if (decision.route() == null || decision.confidence() < MIN_CONFIDENCE) {
                route = keywordRoute(message);
                reason = "low confidence (%.2f), keyword fallback".formatted(decision.confidence());
            } else {
                route = decision.route();
                reason = decision.reason();
            }
        } catch (RuntimeException e) {
            log.warn("Intent router failed, using keyword fallback: {}", e.toString());
            route = keywordRoute(message);
            reason = "router unavailable, keyword fallback";
        }
        metrics.routed(route.name());
        return update("supervisor: %s (%s)".formatted(route, reason), ROUTE, route.name(), ROUTE_REASON, reason);
    }

    /** Deterministic fallback router. */
    public static Route keywordRoute(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        if (m.matches(".*\\b(human|agent|person|manager|warranty|complain|chargeback|dispute|lost)\\b.*")) return Route.ESCALATE;
        if (m.matches(".*\\b(refund|money back|reimburse)\\b.*")) return Route.REFUND;
        if (m.matches(".*\\b(where is|track|tracking|my order|delivery status|shipped|a-\\d{4})\\b.*")) return Route.ORDER_STATUS;
        if (m.matches("^\\s*(hi|hello|hey|thanks|thank you|bye)\\b.*")) return Route.SMALLTALK;
        return Route.KNOWLEDGE;
    }
}
