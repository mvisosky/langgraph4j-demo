package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LLM steps of the refund workflow. Note the split of responsibilities: the LLM only <i>extracts</i> what the
 * customer is asking for; the eligibility decision is deterministic business logic (RefundPolicyService) so the
 * model cannot talk itself (or be talked) into issuing money.
 */
public final class RefundAssistants {

    private RefundAssistants() {}

    public interface RefundRequestExtractor {
        record Request(
                @Description("order id like A-5001, or empty if the customer did not say and it cannot be inferred") String orderId,
                @Description("SKU of the specific item to refund, or empty for the whole order") String sku,
                @Description("why the customer wants a refund, in a few words") String reason,
                @Description("true if the item arrived damaged, defective or wrong") boolean damagedOrWrong) {}

        @SystemMessage("""
                Extract the refund request from the conversation. Use the customer's order list to resolve
                references like "my tent" or "the last order" to an order id and SKU. Never invent an order id that
                is not in the list.
                """)
        @UserMessage("""
                Customer's orders:
                {{orders}}

                Conversation:
                {{transcript}}
                """)
        Request extract(@V("orders") String orders, @V("transcript") String transcript);
    }

    /** Explains the outcome to the customer (smart model). */
    public interface RefundResponder {
        @SystemMessage("""
                You are Acme Outfitters' support assistant. Explain the outcome of the customer's refund request
                clearly and empathetically in under 120 words, using only the facts given. Include the amount,
                the order id and the expected timeline when a refund was issued. If more information is needed,
                ask for exactly what is missing. If it was rejected, explain the policy reason and offer an
                alternative (e.g. warranty claim, store credit, speaking to a specialist).
                """)
        @UserMessage("""
                Customer profile: {{profile}}
                Customer's request: {{request}}
                Outcome facts: {{facts}}
                """)
        String respond(@V("profile") String profile, @V("request") String request, @V("facts") String facts);
    }
}
