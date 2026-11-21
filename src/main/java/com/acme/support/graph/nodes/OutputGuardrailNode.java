package com.acme.support.graph.nodes;

import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import dev.langchain4j.data.message.AiMessage;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.acme.support.graph.SupportState.*;

/**
 * Output guardrail: last line of defence before text reaches the customer. Deterministic, so it cannot be
 * argued with: redacts card numbers and e-mail addresses, blocks system-prompt leakage, and appends a security
 * reminder if the customer pasted card data. Then publishes the final answer into the conversation.
 */
@Component
public class OutputGuardrailNode implements NodeActionWithConfig<SupportState> {

    static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final Pattern PROMPT_LEAK = Pattern.compile("(?i)(you are (acme outfitters'|the order specialist|a strict qa)|system prompt)");

    static final String SECURITY_NOTE = "\n\nFor your security, please never share full card numbers, CVV codes or "
            + "passwords in chat - our team will never ask for them.";
    static final String SAFE_FALLBACK = "Sorry, I ran into a problem preparing that answer. Could you rephrase, "
            + "or would you like me to connect you with a specialist?";

    private final AgentMetrics metrics;

    public OutputGuardrailNode(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String draft = state.str(DRAFT).orElse(SAFE_FALLBACK);
        var result = sanitize(draft);
        result.findings().forEach(f -> metrics.guardrailBlocked("output", f));

        String answer = result.text();
        if ("SENSITIVE_DATA_SHARED".equals(state.str(GUARDRAIL_REASON).orElse(""))) {
            answer += SECURITY_NOTE;
        }
        return update("output_guardrail: " + (result.findings().isEmpty() ? "clean" : "redacted " + result.findings()),
                FINAL_ANSWER, answer,
                MESSAGES, List.of(AiMessage.from(answer)));
    }

    public record Sanitized(String text, List<String> findings) {}

    public static Sanitized sanitize(String text) {
        if (PROMPT_LEAK.matcher(text).find()) {
            return new Sanitized(SAFE_FALLBACK, List.of("PROMPT_LEAK"));
        }
        var findings = new java.util.ArrayList<String>();
        String out = text;
        if (CARD.matcher(out).find()) {
            out = CARD.matcher(out).replaceAll("[redacted card number]");
            findings.add("CARD_NUMBER");
        }
        if (EMAIL.matcher(out).find()) {
            out = EMAIL.matcher(out).replaceAll("[redacted email]");
            findings.add("EMAIL");
        }
        return new Sanitized(out, List.copyOf(findings));
    }
}
