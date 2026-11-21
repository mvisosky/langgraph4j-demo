package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Supervisor (fast model): decides which specialist agent handles the turn.
 */
public interface IntentRouter {

    enum Route {
        @Description("general questions answered from the knowledge base: policies, shipping, warranty, loyalty, product care, payments")
        KNOWLEDGE,
        @Description("questions about the customer's own orders: status, tracking, contents, delivery dates")
        ORDER_STATUS,
        @Description("the customer wants money back for an order or item (refund, return for refund)")
        REFUND,
        @Description("needs a human: warranty claim, billing dispute, lost parcel claim, explicit request for a human, angry customer, or anything the agent cannot do")
        ESCALATE,
        @Description("greetings, thanks, chit-chat, or questions about what the assistant can do")
        SMALLTALK
    }

    record Decision(Route route,
                    @Description("one short sentence justifying the route") String reason,
                    @Description("confidence between 0 and 1") double confidence) {}

    @SystemMessage("""
            You are the supervisor of a team of customer-support specialists at Acme Outfitters.
            Read the conversation and pick exactly one specialist for the customer's LATEST message.
            Use the conversation only to resolve references such as "it" or "that order".
            """)
    @UserMessage("""
            Customer profile: {{profile}}
            What we remember about this customer: {{memories}}

            Conversation so far:
            {{transcript}}

            Latest customer message: {{message}}
            """)
    Decision route(@V("profile") String profile,
                   @V("memories") String memories,
                   @V("transcript") String transcript,
                   @V("message") String message);
}
