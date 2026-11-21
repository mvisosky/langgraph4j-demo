package com.acme.support.memory;

import com.acme.support.config.AgentProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Long-term, cross-conversation semantic memory. Facts are embedded and stored per customer; at the start of
 * every turn the facts most similar to the new message are recalled into the prompt context.
 * <p>
 * Contrast with short-term memory, which is the checkpointed conversation of a single thread.
 */
@Service
public class LongTermMemoryService {

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final AgentProperties.Memory cfg;

    public LongTermMemoryService(@Qualifier("memory") EmbeddingStore<TextSegment> store,
                                 EmbeddingModel embeddingModel,
                                 AgentProperties props) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.cfg = props.memory();
    }

    public List<String> recall(String customerId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .filter(metadataKey("customerId").isEqualTo(customerId))
                .maxResults(cfg.maxRecalled())
                .minScore(cfg.minScore())
                .build();
        return store.search(request).matches().stream()
                .map(m -> m.embedded().text())
                .toList();
    }

    public void remember(String customerId, String threadId, List<String> facts) {
        if (facts.isEmpty()) {
            return;
        }
        var segments = facts.stream()
                .map(f -> TextSegment.from(f, Metadata.from("customerId", customerId)
                        .put("threadId", threadId)
                        .put("createdAt", Instant.now().toString())))
                .toList();
        var embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);
    }

    /** Right to be forgotten. */
    public void forget(String customerId) {
        store.removeAll(metadataKey("customerId").isEqualTo(customerId));
    }
}
