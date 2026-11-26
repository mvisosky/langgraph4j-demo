package com.acme.support.graph;

import com.acme.support.ai.RagAssistants;
import com.acme.support.config.AgentProperties;
import com.acme.support.domain.SupportRepository;
import com.acme.support.observability.AgentMetrics;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.utils.EdgeMappings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.acme.support.graph.Nodes.*;
import static com.acme.support.graph.SupportState.*;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * Corrective RAG as a reusable <b>sub-graph</b>:
 * <pre>
 *   rewrite_query → retrieve → grade_documents ─┬─ RELEVANT → generate_answer → END
 *         ▲                                     ├─ REWRITE  ─┘ (loop back to rewrite_query)
 *         └─────────────────────────────────────┘
 *                                               └─ GIVE_UP  → no_answer → END
 * </pre>
 * <ul>
 *   <li>query rewriting turns "can I send it back?" into a standalone search query</li>
 *   <li>hybrid retrieval (pgvector cosine + Postgres full-text, reciprocal rank fusion)</li>
 *   <li>an LLM grader discards irrelevant chunks; if nothing survives, the query is rewritten and retried</li>
 *   <li>the answer is generated only from graded evidence, and the evidence is passed on to the critic</li>
 * </ul>
 */
@Component
public class KnowledgeRagSubgraph {

    private static final Pattern ORDER_ID = Pattern.compile("(?i)\\b[A-Z]-\\d{4}\\b");
    private static final int MAX_ORDERS_IN_CONTEXT = 3;

    private final RagAssistants.QueryRewriter rewriter;
    private final RagAssistants.DocumentGrader grader;
    private final RagAssistants.GroundedAnswerWriter writer;
    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final EmbeddingModel embeddingModel;
    private final SupportRepository repository;
    private final AgentProperties.Rag cfg;
    private final AgentMetrics metrics;

    public KnowledgeRagSubgraph(RagAssistants.QueryRewriter rewriter,
                                RagAssistants.DocumentGrader grader,
                                RagAssistants.GroundedAnswerWriter writer,
                                @Qualifier("knowledge") EmbeddingStore<TextSegment> knowledgeStore,
                                EmbeddingModel embeddingModel,
                                SupportRepository repository,
                                AgentProperties props,
                                AgentMetrics metrics) {
        this.rewriter = rewriter;
        this.grader = grader;
        this.writer = writer;
        this.knowledgeStore = knowledgeStore;
        this.embeddingModel = embeddingModel;
        this.repository = repository;
        this.cfg = props.rag();
        this.metrics = metrics;
    }

    /**
     * A state sub-graph shares the parent's state type and is flattened into the parent at compile time
     * (its nodes appear as {@code knowledge_agent-<node>}), so the parent's tracing hook covers these nodes too.
     */
    public StateGraph<SupportState> build(StateSerializer<SupportState> serializer) throws GraphStateException {
        return new StateGraph<>(SupportState.SCHEMA, serializer)
                .addNode(REWRITE_QUERY, node_async(this::rewriteQuery))
                .addNode(RETRIEVE, node_async(this::retrieve))
                .addNode(GRADE_DOCUMENTS, node_async(this::gradeDocuments))
                .addNode(GENERATE_ANSWER, node_async(this::generateAnswer))
                .addNode(NO_ANSWER, node_async(this::noAnswer))
                .addEdge(START, REWRITE_QUERY)
                .addEdge(REWRITE_QUERY, RETRIEVE)
                .addEdge(RETRIEVE, GRADE_DOCUMENTS)
                .addConditionalEdges(GRADE_DOCUMENTS,
                        edge_async(state -> state.str(RAG_OUTCOME).orElse("GIVE_UP")),
                        EdgeMappings.builder()
                                .to(GENERATE_ANSWER, "RELEVANT")
                                .to(REWRITE_QUERY, "REWRITE")
                                .to(NO_ANSWER, "GIVE_UP")
                                .build())
                .addEdge(GENERATE_ANSWER, END)
                .addEdge(NO_ANSWER, END);
    }

    Map<String, Object> rewriteQuery(SupportState state, RunnableConfig config) {
        int attempt = state.integer(RAG_ATTEMPTS) + 1;
        String previous = state.str(RAG_QUERY).orElse("none (first attempt)");
        String query;
        try {
            query = rewriter.rewrite(state.recentTranscript(6), state.lastUserText(), previous).trim();
        } catch (RuntimeException e) {
            query = state.lastUserText();   // degrade gracefully to the raw question
        }
        if (query.isBlank()) {
            query = state.lastUserText();
        }
        return update("rewrite_query #%d: %s".formatted(attempt, query), RAG_QUERY, query, RAG_ATTEMPTS, attempt);
    }

    Map<String, Object> retrieve(SupportState state, RunnableConfig config) {
        String query = state.str(RAG_QUERY).orElse(state.lastUserText());
        var request = EmbeddingSearchRequest.builder()
                .query(query)                                           // full-text half of hybrid search
                .queryEmbedding(embeddingModel.embed(query).content())  // vector half
                .maxResults(cfg.maxResults())
                .minScore(0.0)   // RRF scores are small rank-based numbers; relevance is decided by the grader
                .build();
        List<String> documents = knowledgeStore.search(request).matches().stream()
                .map(m -> m.embedded().text())
                .toList();
        return update("retrieve: %d chunk(s) for '%s'".formatted(documents.size(), query), DOCUMENTS, documents);
    }

    Map<String, Object> gradeDocuments(SupportState state, RunnableConfig config) {
        List<String> documents = state.strings(DOCUMENTS);
        int attempts = state.integer(RAG_ATTEMPTS);
        List<String> relevant = new ArrayList<>();

        if (!documents.isEmpty()) {
            String numbered = String.join("\n\n", IntStream.range(0, documents.size())
                    .mapToObj(i -> "[%d] %s".formatted(i, documents.get(i))).toList());
            try {
                var grade = grader.grade(state.lastUserText(), numbered);
                if (grade.relevant() != null) {
                    grade.relevant().stream()
                            .filter(i -> i != null && i >= 0 && i < documents.size())
                            .distinct()
                            .forEach(i -> relevant.add(documents.get(i)));
                }
            } catch (RuntimeException e) {
                relevant.addAll(documents);   // grader down: keep everything, the critic is the next safety net
            }
        }

        String outcome = !relevant.isEmpty() ? "RELEVANT"
                : attempts <= cfg.maxQueryRewrites() ? "REWRITE"
                : "GIVE_UP";
        if (!"REWRITE".equals(outcome)) {
            metrics.ragRetrieval(relevant.size(), attempts - 1);
        }
        return update("grade_documents: %d/%d relevant -> %s".formatted(relevant.size(), documents.size(), outcome),
                DOCUMENTS, relevant, RAG_OUTCOME, outcome);
    }

    Map<String, Object> generateAnswer(SupportState state, RunnableConfig config) {
        String policy = String.join("\n---\n", state.strings(DOCUMENTS));
        String orders = orderContext(state);
        String profile = state.str(CUSTOMER_PROFILE).orElse("unknown");
        String draft = writer.answer(
                profile,
                orders,
                String.join("; ", state.strings(MEMORIES)),
                state.recentTranscript(6),
                policy,
                state.lastUserText());
        // The critic and reviser see only EVIDENCE. Everything the writer was allowed to use must be in it - the
        // customer's tier and order facts included - or a correctly personalised answer is flagged as ungrounded
        // and the reviser strips the personalisation back out.
        var evidence = new StringBuilder(policy);
        if (!"unknown".equals(profile)) {
            evidence.append("\n---\nCustomer profile (from our records): ").append(profile);
        }
        if (!"none".equals(orders)) {
            evidence.append("\n---\nCustomer's order details (from our records):\n").append(orders);
        }
        return update("generate_answer: drafted %d chars".formatted(draft.length()), DRAFT, draft, EVIDENCE, evidence.toString());
    }

    /**
     * Order facts for orders the customer mentions (this message or the recent conversation). Deterministic and
     * customer-scoped: {@link SupportRepository#order} only ever returns the authenticated customer's own orders,
     * so a made-up or someone else's order id simply comes back as "not found".
     */
    private String orderContext(SupportState state) {
        var ids = new LinkedHashSet<String>();
        var text = state.lastUserText() + "\n" + state.recentTranscript(6);
        var matcher = ORDER_ID.matcher(text);
        while (matcher.find() && ids.size() < MAX_ORDERS_IN_CONTEXT) {
            ids.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        if (ids.isEmpty()) {
            return "none";
        }
        try {
            String customerId = state.customerId();
            return ids.stream()
                    .map(id -> repository.order(customerId, id)
                            .map(o -> o.oneLine() + (o.deliveredAt() == null ? " | not yet delivered" : ""))
                            .orElse(id + " | not found on this customer's account"))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return "none";   // degrade to a policy-only answer rather than failing the turn
        }
    }

    Map<String, Object> noAnswer(SupportState state, RunnableConfig config) {
        String draft = "I couldn't find a policy that answers that, and I'd rather not guess. "
                + "Would you like me to pass your question to one of our specialists?";
        return update("no_answer: knowledge base had nothing relevant", DRAFT, draft,
                EVIDENCE, "No relevant policy documents were found for the question.");
    }
}
