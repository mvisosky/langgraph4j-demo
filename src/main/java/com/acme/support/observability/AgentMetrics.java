package com.acme.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level agent metrics, exported over OTLP alongside the technical ones.
 * These are what you would alert on: guardrail block rate, escalation rate, human-approval backlog,
 * how often the critic forces a rewrite, RAG retrieval quality.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void routed(String route) {
        Counter.builder("agent.route").description("Supervisor routing decisions")
                .tag("route", route).register(registry).increment();
    }

    public void guardrailBlocked(String stage, String category) {
        Counter.builder("agent.guardrail.blocked").description("Messages blocked or redacted by guardrails")
                .tag("stage", stage).tag("category", category).register(registry).increment();
    }

    public void critique(String verdict) {
        Counter.builder("agent.critic.verdict").description("Reflection loop verdicts")
                .tag("verdict", verdict).register(registry).increment();
    }

    public void refund(String outcome) {
        Counter.builder("agent.refund").description("Refund decisions")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void humanApproval(String event) {
        Counter.builder("agent.hitl").description("Human-in-the-loop events (requested/approved/rejected)")
                .tag("event", event).register(registry).increment();
    }

    public void ragRetrieval(int relevantDocuments, int rewrites) {
        DistributionSummary.builder("agent.rag.relevant_documents")
                .description("Documents judged relevant after grading").register(registry).record(relevantDocuments);
        Counter.builder("agent.rag.query_rewrites").description("Corrective-RAG query rewrites")
                .register(registry).increment(rewrites);
    }

    public void memoryWritten(int facts) {
        Counter.builder("agent.memory.facts_written").description("Long-term memory facts stored")
                .register(registry).increment(facts);
    }

    public void ticketCreated(String priority) {
        Counter.builder("agent.ticket.created").description("Escalation tickets created")
                .tag("priority", priority).register(registry).increment();
    }
}
