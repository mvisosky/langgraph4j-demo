package com.acme.support.graph.nodes;

import com.acme.support.ai.InputGuard;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.acme.support.graph.SupportState.*;

/**
 * Input guardrail, layered:
 * <ol>
 *   <li>deterministic checks (empty / oversized input, well-known prompt-injection phrases) – free and instant</li>
 *   <li>an LLM classifier on the fast model for everything that passes</li>
 * </ol>
 * The LLM layer <b>fails open</b>: if the classifier is unavailable the turn continues, because the real
 * protections are structural (customer-scoped tools, refund policy in code, human approval for large refunds,
 * output redaction). A system without those would need to fail closed instead.
 */
@Component
public class InputGuardrailNode implements NodeActionWithConfig<SupportState> {

    private static final Logger log = LoggerFactory.getLogger(InputGuardrailNode.class);

    static final int MAX_CHARS = 4000;
    static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts?|rules)"
                    + "|(reveal|print|show|repeat)\\s+(your|the)\\s+(system\\s+)?(prompt|instructions)"
                    + "|you\\s+are\\s+now\\s+(a|an|in)\\b|developer\\s+mode|jailbreak"
                    + "|act\\s+as\\s+(an?\\s+)?(admin|administrator|system|developer)"
                    + "|customer[_\\s]?id\\s*[:=]|another\\s+customer'?s\\s+(order|account))");

    private final InputGuard guard;
    private final AgentMetrics metrics;

    public InputGuardrailNode(InputGuard guard, AgentMetrics metrics) {
        this.guard = guard;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String text = state.lastUserText();

        Optional<String> heuristic = heuristicViolation(text);
        if (heuristic.isPresent()) {
            metrics.guardrailBlocked("input", heuristic.get());
            return update("input_guardrail: BLOCK (rule: %s)".formatted(heuristic.get()),
                    GUARDRAIL_VERDICT, "BLOCK", GUARDRAIL_REASON, heuristic.get());
        }

        try {
            InputGuard.Verdict verdict = guard.classify(text);
            String category = verdict.category() == null ? "SAFE" : verdict.category().name();
            if (!verdict.allowed()) {
                metrics.guardrailBlocked("input", category);
                return update("input_guardrail: BLOCK (llm: %s - %s)".formatted(category, verdict.reason()),
                        GUARDRAIL_VERDICT, "BLOCK", GUARDRAIL_REASON, category);
            }
            return update("input_guardrail: ALLOW (%s)".formatted(category),
                    GUARDRAIL_VERDICT, "ALLOW", GUARDRAIL_REASON, category);
        } catch (RuntimeException e) {
            log.warn("Input guard classifier unavailable, failing open: {}", e.toString());
            return update("input_guardrail: ALLOW (classifier unavailable - fail open)",
                    GUARDRAIL_VERDICT, "ALLOW", GUARDRAIL_REASON, "UNCHECKED");
        }
    }

    /** Cheap deterministic pre-checks; returns the violated rule, if any. */
    public static Optional<String> heuristicViolation(String text) {
        if (text == null || text.isBlank()) {
            return Optional.of("EMPTY");
        }
        if (text.length() > MAX_CHARS) {
            return Optional.of("TOO_LONG");
        }
        if (INJECTION.matcher(text).find()) {
            return Optional.of("PROMPT_INJECTION");
        }
        return Optional.empty();
    }
}
