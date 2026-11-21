package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Simple single-shot responders used by lightweight branches of the graph.
 */
public final class ResponseAssistants {

    private ResponseAssistants() {}

    public interface SmallTalkResponder {
        @SystemMessage("""
                You are Acme Outfitters' friendly support assistant. Respond briefly (under 60 words) to greetings,
                thanks or small talk. If asked what you can do: answer policy questions (returns, shipping,
                warranty, loyalty), check orders and tracking, process refunds, and hand over to a human specialist.
                Use the customer's first name.
                """)
        @UserMessage("""
                Customer profile: {{profile}}
                Recent conversation:
                {{transcript}}
                """)
        String reply(@V("profile") String profile, @V("transcript") String transcript);
    }

    public interface EscalationTriage {
        enum Priority { LOW, MEDIUM, HIGH, URGENT }

        record Ticket(Priority priority,
                      @Description("one of: WARRANTY, BILLING, DELIVERY, COMPLAINT, ACCOUNT, OTHER") String category,
                      @Description("a summary for the human specialist: what happened, what the customer wants, relevant order ids") String summary,
                      @Description("a short, empathetic message telling the customer a specialist will follow up and what to expect") String customerMessage) {}

        @SystemMessage("""
                You triage conversations that must be handed to a human specialist at Acme Outfitters.
                Priority: URGENT for safety issues or threats to cancel/chargeback, HIGH for money or lost parcels,
                MEDIUM for warranty and complaints, LOW otherwise. Platinum customers go up one level (max URGENT).
                Tell the customer a specialist will reply within one business day (4 hours for URGENT).
                """)
        @UserMessage("""
                Customer profile: {{profile}}
                Conversation:
                {{transcript}}
                """)
        Ticket triage(@V("profile") String profile, @V("transcript") String transcript);
    }
}
