package com.acme.support.graph;

import com.acme.support.ai.RagAssistants;
import com.acme.support.config.AgentProperties;
import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.Domain.OrderItem;
import com.acme.support.domain.SupportRepository;
import com.acme.support.observability.AgentMetrics;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.acme.support.graph.SupportState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The knowledge agent must see the customer's own order when the question is about it. */
@SuppressWarnings("unchecked")
class KnowledgeRagOrderContextTest {

    static final Order A5002 = new Order("A-5002", "C-1001", "SHIPPED", new BigDecimal("129.00"),
            OffsetDateTime.now().minusDays(3), null, "FedEx", "7489 2213 0098",
            List.of(new OrderItem("JKT-RAIN-M", "Packable rain jacket (M)", 1, new BigDecimal("129.00"), false)));

    RagAssistants.GroundedAnswerWriter writer = mock(RagAssistants.GroundedAnswerWriter.class);
    SupportRepository repository = mock(SupportRepository.class);
    KnowledgeRagSubgraph subgraph;

    @BeforeEach
    void setUp() {
        subgraph = new KnowledgeRagSubgraph(mock(RagAssistants.QueryRewriter.class), mock(RagAssistants.DocumentGrader.class),
                writer, (EmbeddingStore<TextSegment>) mock(EmbeddingStore.class), mock(EmbeddingModel.class), repository,
                new AgentProperties("anthropic", null, null, null, null, new AgentProperties.Rag(5, 2), null, null, null, null),
                new AgentMetrics(new SimpleMeterRegistry()));
        when(writer.answer(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn("draft");
        when(repository.order("C-1001", "A-5002")).thenReturn(Optional.of(A5002));
    }

    private SupportState state(String text) {
        return new SupportState(Map.of(
                MESSAGES, List.of(UserMessage.from(text)),
                CUSTOMER_ID, "C-1001",
                CUSTOMER_PROFILE, "Alice Martin (id C-1001), GOLD member",
                DOCUMENTS, List.of("Returns accepted within 30 days of delivery.")));
    }

    @Test
    void mentionedOrderIsLoadedForTheCustomerAndPassedToTheWriterAndCritic() {
        var update = subgraph.generateAnswer(state("What's the returns policy for order a-5002?"), RunnableConfig.builder().build());

        var orders = ArgumentCaptor.forClass(String.class);
        verify(writer).answer(anyString(), orders.capture(), anyString(), anyString(), anyString(), anyString());
        assertThat(orders.getValue()).contains("A-5002", "Packable rain jacket", "not yet delivered");
        verify(repository).order("C-1001", "A-5002");   // always scoped to the authenticated customer
        // the critic/reviser see only EVIDENCE: it must carry the order AND the tier the writer used
        assertThat((String) update.get(EVIDENCE))
                .contains("Packable rain jacket", "Returns accepted within 30 days", "GOLD member");
    }

    @Test
    void unknownOrderIsReportedAsNotFoundNotInvented() {
        when(repository.order(anyString(), anyString())).thenReturn(Optional.empty());

        subgraph.generateAnswer(state("returns policy for A-9999?"), RunnableConfig.builder().build());

        var orders = ArgumentCaptor.forClass(String.class);
        verify(writer).answer(anyString(), orders.capture(), anyString(), anyString(), anyString(), anyString());
        assertThat(orders.getValue()).contains("A-9999", "not found");
    }

    @Test
    void noOrderMentionedMeansNoLookupAndNoOrderEvidence() {
        var update = subgraph.generateAnswer(state("How long do I have to return something?"), RunnableConfig.builder().build());

        verify(repository, never()).order(any(), any());
        assertThat((String) update.get(EVIDENCE))
                .contains("Returns accepted within 30 days of delivery.", "GOLD member")
                .doesNotContain("order details");
    }

    @Test
    void lookupFailureDegradesToAPolicyOnlyAnswer() {
        when(repository.order(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));

        var update = subgraph.generateAnswer(state("returns policy for A-5002?"), RunnableConfig.builder().build());

        assertThat(update.get(DRAFT)).isEqualTo("draft");
    }
}
