package com.acme.support.graph.nodes;

import com.acme.support.ai.ReflectionAssistants;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * Reflection / LLM-as-judge: reviews the specialist's draft against the evidence it was given. The conditional
 * edge after this node loops to the reviser while the verdict is REVISE and the revision budget allows.
 */
@Component
public class CriticNode implements NodeActionWithConfig<SupportState> {

    private static final Logger log = LoggerFactory.getLogger(CriticNode.class);

    private final ReflectionAssistants.Critic critic;
    private final AgentMetrics metrics;

    public CriticNode(ReflectionAssistants.Critic critic, AgentMetrics metrics) {
        this.critic = critic;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String draft = state.str(DRAFT).orElse("");
        if (draft.isBlank()) {
            return update("critic: no draft, skipped", CRITIC_VERDICT, "PASS");
        }
        try {
            var critique = critic.review(state.lastUserText(), state.str(EVIDENCE).orElse("(none)"), draft);
            String verdict = critique.verdict() == null ? "PASS" : critique.verdict().name();
            String issues = critique.issues() == null ? "" : String.join("\n- ", critique.issues());
            metrics.critique(verdict);
            return update("critic: %s (score %d)%s".formatted(verdict, critique.score(), issues.isBlank() ? "" : " - " + issues),
                    CRITIC_VERDICT, verdict,
                    CRITIQUE, issues.isBlank() ? "" : "- " + issues);
        } catch (RuntimeException e) {
            log.warn("Critic unavailable, passing draft through: {}", e.toString());
            return update("critic: unavailable, draft passed through", CRITIC_VERDICT, "PASS");
        }
    }
}
