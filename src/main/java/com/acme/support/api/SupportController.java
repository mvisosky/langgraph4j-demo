package com.acme.support.api;

import com.acme.support.api.ApiModels.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SupportController {

    private final SupportAgentService service;

    public SupportController(SupportAgentService service) {
        this.service = service;
    }

    /** Start a conversation thread for a customer (a real system would take the customer from the auth token). */
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationStarted start(@Valid @RequestBody StartConversationRequest request) {
        return service.startConversation(request.customerId());
    }

    /** Send a customer message and wait for the agent's answer. */
    @PostMapping("/conversations/{threadId}/messages")
    public TurnResult send(@PathVariable String threadId, @Valid @RequestBody MessageRequest request) {
        return service.sendMessage(threadId, request.message());
    }

    /** Same as above, streamed as Server-Sent Events: one "node" event per graph step, then a "result" event. */
    @PostMapping(value = "/conversations/{threadId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String threadId, @Valid @RequestBody MessageRequest request) {
        return service.streamMessage(threadId, request.message());
    }

    @GetMapping("/conversations/{threadId}")
    public ConversationView conversation(@PathVariable String threadId) {
        return service.conversation(threadId);
    }

    /** Checkpoint history of the thread (time travel). */
    @GetMapping("/conversations/{threadId}/history")
    public List<CheckpointView> history(@PathVariable String threadId) {
        return service.history(threadId);
    }

    /** Approval queue for refund specialists. */
    @GetMapping("/approvals")
    public List<PendingApproval> approvals() {
        return service.pendingApprovals();
    }

    /** A specialist approves or rejects a paused refund; the graph resumes from its checkpoint. */
    @PostMapping("/conversations/{threadId}/approval")
    public TurnResult approve(@PathVariable String threadId, @Valid @RequestBody ApprovalRequest request) {
        return service.resolveApproval(threadId, request);
    }

    /** Right to be forgotten: delete the customer's long-term memories. */
    @DeleteMapping("/customers/{customerId}/memory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable String customerId) {
        service.forgetCustomer(customerId);
    }

    /** The graph as a Mermaid diagram (paste into https://mermaid.live). */
    @GetMapping(value = "/graph", produces = MediaType.TEXT_PLAIN_VALUE)
    public String graph() {
        return service.mermaid();
    }
}
