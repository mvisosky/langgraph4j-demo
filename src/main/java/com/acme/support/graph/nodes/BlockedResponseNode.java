package com.acme.support.graph.nodes;

import com.acme.support.graph.SupportState;
import dev.langchain4j.data.message.AiMessage;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Terminal node for blocked input: a fixed, safe reply. No LLM call, so nothing to manipulate. */
@Component
public class BlockedResponseNode implements NodeActionWithConfig<SupportState> {

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String reason = state.str(GUARDRAIL_REASON).orElse("");
        String reply = switch (reason) {
            case "EMPTY" -> "It looks like your message was empty. How can I help you today?";
            case "TOO_LONG" -> "That message is a bit long for me. Could you summarise what you need help with?";
            case "OFF_TOPIC" -> "I can only help with Acme Outfitters orders, products, shipping, returns, billing "
                    + "and our loyalty program. Is there something along those lines I can do for you?";
            case "ABUSIVE" -> "I want to help, but I can't continue with that language. If something has gone "
                    + "wrong with an order, tell me about it and I'll do my best to fix it.";
            default -> "Sorry, I can't help with that request. I can answer questions about your orders, "
                    + "returns and refunds, shipping, warranty and our loyalty program.";
        };
        return update("blocked_response: " + reason,
                FINAL_ANSWER, reply,
                MESSAGES, List.of(AiMessage.from(reply)));
    }
}
