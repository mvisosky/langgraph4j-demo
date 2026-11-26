package com.acme.support.graph.nodes;

import com.acme.support.ai.RefundAssistants;
import com.acme.support.domain.SupportRepository;
import com.acme.support.graph.SupportState;
import com.acme.support.knowledge.PolicySearch;
import com.acme.support.observability.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IssueRefundNodeTest {

    SupportRepository repository = mock(SupportRepository.class);
    RefundAssistants.RefundResponder responder = mock(RefundAssistants.RefundResponder.class);
    PolicySearch policySearch = mock(PolicySearch.class);
    IssueRefundNode node;

    @BeforeEach
    void setUp() {
        node = new IssueRefundNode(repository, responder, policySearch, new AgentMetrics(new SimpleMeterRegistry()));
        when(repository.recordRefund(anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(7L);
        when(responder.respond(anyString(), anyString(), anyString(), anyString())).thenReturn("Your refund is on its way.");
    }

    private SupportState state() {
        return new SupportState(Map.of(
                CUSTOMER_ID, "C-1003", CUSTOMER_PROFILE, "Carol Dubois (id C-1003), PLATINUM member",
                REFUND_ORDER_ID, "A-5006", REFUND_AMOUNT, 160.0, REFUND_REASON, "boots don't fit",
                APPROVAL_DECISION, "APPROVED", APPROVER, "dana@acme.example", TURN, 1,
                EVIDENCE, "Policy decision: NEEDS_APPROVAL",
                MESSAGES, java.util.List.of(dev.langchain4j.data.message.UserMessage.from("do I have to send the boots back?"))));
    }

    @Test
    void policyExcerptsReachTheResponderAndTheCritic() {
        when(policySearch.excerpts(anyString())).thenReturn("Return labels are free for Gold and Platinum members.");

        var update = node.apply(state(), RunnableConfig.builder().threadId("t-1").build());

        var policy = ArgumentCaptor.forClass(String.class);
        verify(responder).respond(anyString(), anyString(), anyString(), policy.capture());
        assertThat(policy.getValue()).contains("free for Gold and Platinum");
        // the critic only sees EVIDENCE, so it must include the excerpts and the tier the responder used
        assertThat((String) update.get(EVIDENCE))
                .contains("free for Gold and Platinum", "PLATINUM member", "Refund R-7 ISSUED");
        assertThat(update.get(DRAFT)).isEqualTo("Your refund is on its way.");
    }

    @Test
    void refundIsStillIssuedWhenThePolicyLookupFindsNothing() {
        when(policySearch.excerpts(anyString())).thenReturn("none");

        var update = node.apply(state(), RunnableConfig.builder().threadId("t-1").build());

        verify(repository).recordRefund(eq("t-1:1:A-5006"), eq("A-5006"), eq("C-1003"), eq(new BigDecimal("160.00")),
                anyString(), eq("ISSUED"), eq("dana@acme.example"), eq("t-1"));
        assertThat((String) update.get(EVIDENCE)).doesNotContain("Policy excerpts");
    }
}
