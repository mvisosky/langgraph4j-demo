package com.acme.support.config;

import com.acme.support.graph.SupportState;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.langchain4j.serializer.jackson.LC4jJacksonStateSerializer;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Everything the agent persists lives in one Postgres (with pgvector):
 * <ul>
 *   <li><b>knowledge</b> store – RAG corpus, hybrid search (vector + full-text, fused with reciprocal rank fusion)</li>
 *   <li><b>memory</b> store – long-term semantic memory: facts about each customer, filtered by customer id</li>
 *   <li><b>checkpoints</b> – LangGraph4j graph state after every node: conversation (short-term) memory,
 *       time travel / state history, and pause-resume for human-in-the-loop approval</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfig {

    @Bean
    @Qualifier("knowledge")
    EmbeddingStore<TextSegment> knowledgeStore(DataSource dataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("kb_embeddings")
                .dimension(AiModelConfig.EMBEDDING_DIMENSION)
                .createTable(true)
                .searchMode(PgVectorEmbeddingStore.SearchMode.HYBRID)
                .textSearchConfig("english")
                .build();
    }

    @Bean
    @Qualifier("memory")
    EmbeddingStore<TextSegment> memoryStore(DataSource dataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("customer_memory")
                .dimension(AiModelConfig.EMBEDDING_DIMENSION)
                .createTable(true)
                .build();
    }

    /**
     * JSON (Jackson) serializer that understands LangChain4j chat messages. JSON keeps checkpoints human-readable
     * in Postgres (JSONB) and is also what LangGraph4j Studio uses to render state.
     */
    @Bean
    StateSerializer<SupportState> supportStateSerializer() {
        return new LC4jJacksonStateSerializer<>(SupportState::new);
    }

    @Bean
    BaseCheckpointSaver checkpointSaver(DataSource dataSource, StateSerializer<SupportState> serializer) throws Exception {
        return PostgresSaverV2.builder()
                .datasource(dataSource)
                .stateSerializer(serializer)
                .createTables(true)
                .build();
    }
}
