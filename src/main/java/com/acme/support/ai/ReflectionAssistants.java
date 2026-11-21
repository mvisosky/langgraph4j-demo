package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Reflection pattern: a critic reviews every draft before it reaches the customer, and a reviser fixes it.
 * The graph loops critic → reviser → critic until the draft passes or the revision budget is spent.
 */
public final class ReflectionAssistants {

    private ReflectionAssistants() {}

    public interface Critic {
        enum Verdict { PASS, REVISE }

        record Critique(Verdict verdict,
                        @Description("1 (unusable) to 5 (excellent)") int score,
                        @Description("concrete problems; empty when PASS") List<String> issues) {}

        @SystemMessage("""
                You are a strict QA reviewer for customer-support replies. Check the draft against the evidence:
                1. Grounded: every factual claim (policy, amount, date, status) is supported by the evidence.
                2. Answers the customer's actual question.
                3. Safe: no promises beyond policy, no other customers' data, no full card numbers.
                4. Tone: friendly, concise, professional.
                Reply PASS if the draft is good enough to send (score >= 4), otherwise REVISE with specific issues.
                """)
        @UserMessage("""
                Customer question: {{question}}

                Evidence available to the agent:
                {{evidence}}

                Draft reply:
                {{draft}}
                """)
        Critique review(@V("question") String question, @V("evidence") String evidence, @V("draft") String draft);
    }

    public interface Reviser {
        @SystemMessage("""
                Rewrite the customer-support draft to fix every listed issue. Use only facts from the evidence.
                Output only the improved reply.
                """)
        @UserMessage("""
                Customer question: {{question}}
                Evidence:
                {{evidence}}
                Draft:
                {{draft}}
                Issues to fix:
                {{issues}}
                """)
        String revise(@V("question") String question, @V("evidence") String evidence,
                      @V("draft") String draft, @V("issues") String issues);
    }
}
