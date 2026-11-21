package com.acme.support.graph.nodes;

import com.acme.support.ai.ResponseAssistants;
import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.observability.AgentMetrics;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Hand-off to humans: LLM triage (priority, category, summary) then a ticket in the helpdesk table. */
@Component
public class EscalationNode implements NodeActionWithConfig<SupportState> {

    private final ResponseAssistants.EscalationTriage triage;
    private final SupportRepository repository;
    private final AgentMetrics metrics;

    public EscalationNode(ResponseAssistants.EscalationTriage triage, SupportRepository repository, AgentMetrics metrics) {
        this.triage = triage;
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        var ticket = triage.triage(state.str(CUSTOMER_PROFILE).orElse(""), state.recentTranscript(10));
        String priority = ticket.priority() == null ? "MEDIUM" : ticket.priority().name();
        long id = repository.createTicket(state.customerId(), config.threadId().orElse(null),
                priority, ticket.category() == null ? "OTHER" : ticket.category(), ticket.summary());
        metrics.ticketCreated(priority);

        String draft = ticket.customerMessage() + " Your reference is T-%d.".formatted(id);
        return update("escalation_agent: ticket T-%d %s/%s".formatted(id, priority, ticket.category()),
                TICKET_ID, "T-" + id,
                DRAFT, draft,
                EVIDENCE, "Ticket T-%d created with priority %s, category %s. Summary for specialist: %s"
                        .formatted(id, priority, ticket.category(), ticket.summary()));
    }
}
