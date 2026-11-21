package com.acme.support.graph.nodes;

import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Parallel branch 1 of the context fan-out: loads the customer profile from the CRM tables. */
@Component
public class CustomerContextNode implements NodeActionWithConfig<SupportState> {

    private final SupportRepository repository;

    public CustomerContextNode(SupportRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String customerId = state.customerId();
        String profile = repository.customer(customerId)
                .map(c -> c.promptProfile() + ", %d orders on file".formatted(repository.orders(customerId).size()))
                .orElse("Unknown customer " + customerId);
        return update("load_customer: " + profile, CUSTOMER_PROFILE, profile);
    }
}
