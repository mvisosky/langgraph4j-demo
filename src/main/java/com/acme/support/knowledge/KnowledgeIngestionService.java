package com.acme.support.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Idempotent RAG ingestion at startup: each markdown file under {@code classpath:knowledge/} is chunked,
 * embedded and stored. A content hash per source means unchanged documents are skipped and edited ones are
 * re-indexed (old chunks deleted first) - the same pattern you'd use in a real ingestion pipeline.
 */
@Component
public class KnowledgeIngestionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final JdbcClient jdbc;

    public KnowledgeIngestionService(@Qualifier("knowledge") EmbeddingStore<TextSegment> store,
                                     EmbeddingModel embeddingModel,
                                     JdbcClient jdbc) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
        for (Resource resource : resources) {
            String source = resource.getFilename();
            String text = resource.getContentAsString(StandardCharsets.UTF_8);
            String hash = sha256(text);

            var existing = jdbc.sql("SELECT sha256 FROM kb_ingestion WHERE source = :s")
                    .param("s", source).query(String.class).optional();
            if (existing.isPresent() && existing.get().equals(hash)) {
                continue;
            }
            if (existing.isPresent()) {
                store.removeAll(metadataKey("source").isEqualTo(source));
            }

            String title = text.lines().findFirst().orElse(source).replaceFirst("^#\\s*", "");
            var document = Document.from(text, Metadata.from("source", source).put("title", title));

            var segmentCount = new int[]{0};
            EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(700, 100))
                    // Contextual chunk headers: prefix each chunk with its document title so a chunk like
                    // "Items must be unused..." is still retrievable for "returns policy" queries.
                    .textSegmentTransformer(segment -> {
                        segmentCount[0]++;
                        return TextSegment.from(title + "\n" + segment.text(), segment.metadata());
                    })
                    .embeddingModel(embeddingModel)
                    .embeddingStore(store)
                    .build()
                    .ingest(document);

            jdbc.sql("""
                            INSERT INTO kb_ingestion (source, sha256, segments) VALUES (:s, :h, :n)
                            ON CONFLICT (source) DO UPDATE SET sha256 = :h, segments = :n, ingested_at = now()""")
                    .param("s", source).param("h", hash).param("n", segmentCount[0])
                    .update();
            log.info("Ingested knowledge document '{}' ({} chunks)", source, segmentCount[0]);
        }
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
