package com.acme.support.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request/response payloads of the REST API. */
public final class ApiModels {

    private ApiModels() {}

    public record StartConversationRequest(@NotBlank String customerId) {}

    public record ConversationStarted(String threadId, String customerId) {}

    public record MessageRequest(@NotBlank @Size(max = 8000) String message) {}

    public record ApprovalRequest(@NotNull Boolean approved, @NotBlank String approver, String note) {}

    public enum TurnStatus { COMPLETED, AWAITING_APPROVAL }

    public record RefundInfo(String orderId, Double amount, String decision, String policyNote, String approvalDecision) {}

    /** Result of one graph run (a customer turn or a resumed approval). */
    public record TurnResult(
            String threadId,
            TurnStatus status,
            String answer,
            String route,
            RefundInfo refund,
            String ticketId,
            int revisions,
            List<String> agentTrace,
            String traceId,       // OpenTelemetry trace id: paste into Jaeger to see the whole run
            String checkpointId) {}

    public record Message(String role, String text) {}

    public record ConversationView(String threadId, String customerId, int turn, boolean awaitingApproval,
                                   String conversationSummary, List<Message> messages) {}

    public record CheckpointView(String checkpointId, String node, String nextNode, Instant savedAt) {}

    public record PendingApproval(String threadId, String customerId, String orderId, BigDecimal amount,
                                  String reason, String policyNote, Instant requestedAt) {}

    public record NodeEvent(String node, String trace) {}
}
