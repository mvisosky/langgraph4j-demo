package com.acme.support.knowledge;

import com.acme.support.config.AgentProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plain hybrid (vector + full-text) lookup in the policy knowledge base, for steps that only need a few relevant
 * excerpts and not the full corrective-RAG loop (for example, grounding the wording of a refund confirmation).
 * Never throws: if the store is unavailable the caller simply gets no excerpts and degrades to what it already has.
 */
@Component
public class PolicySearch {

    private static final Logger log = LoggerFactory.getLogger(PolicySearch.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final int maxResults;

    public PolicySearch(@Qualifier("knowledge") EmbeddingStore<TextSegment> store,
                        EmbeddingModel embeddingModel,
                        AgentProperties props) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.maxResults = props.rag().maxResults();
    }

    /** The most relevant policy chunks for the query, or an empty list if the lookup fails. */
    public List<String> find(String query) {
        try {
            var request = EmbeddingSearchRequest.builder()
                    .query(query)
                    .queryEmbedding(embeddingModel.embed(query).content())
                    .maxResults(maxResults)
                    .minScore(0.0)   // hybrid scores are rank-based; we take the top few
                    .build();
            return store.search(request).matches().stream().map(m -> m.embedded().text()).toList();
        } catch (RuntimeException e) {
            log.warn("Policy lookup failed, continuing without excerpts: {}", e.toString());
            return List.of();
        }
    }

    /** Excerpts joined for a prompt, or "none" when nothing was found. */
    public String excerpts(String query) {
        var found = find(query);
        return found.isEmpty() ? "none" : String.join("\n---\n", found);
    }
}
