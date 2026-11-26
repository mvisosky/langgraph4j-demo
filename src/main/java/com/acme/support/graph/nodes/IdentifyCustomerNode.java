package com.acme.support.graph.nodes;

import com.acme.support.domain.IdentityService;
import com.acme.support.graph.SupportState;
import dev.langchain4j.data.message.AiMessage;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * Runs instead of the normal pipeline while a conversation has no verified customer. Identity is established by
 * {@link IdentityService} (email + one-time code) - deterministic code, no LLM - and only on success is
 * {@code customer_id} written to state, which unlocks the rest of the graph from the next turn on.
 * Every turn through this node ends with a fixed reply.
 */
@Component
public class IdentifyCustomerNode implements NodeActionWithConfig<SupportState> {

    private final IdentityService identity;

    public IdentifyCustomerNode(IdentityService identity) {
        this.identity = identity;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String threadId = config.threadId().orElseThrow(() -> new IllegalStateException("thread id missing"));
        var outcome = identity.handle(threadId, state.lastUserText());
        return update(outcome.customerId() != null ? "identify_customer: verified" : "identify_customer: pending",
                CUSTOMER_ID, outcome.customerId(),     // null (omitted) unless verification succeeded
                FINAL_ANSWER, outcome.reply(),
                MESSAGES, List.of(AiMessage.from(outcome.reply())));
    }
}
