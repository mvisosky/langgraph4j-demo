package com.acme.support.graph;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared state of the support graph.
 * <p>
 * State is persisted after every node by the checkpoint saver, which is what gives the agent
 * <b>short-term memory</b> (the whole conversation survives restarts, keyed by thread id) and lets a run
 * <b>pause for human approval</b> and resume later.
 * <p>
 * Values are kept to JSON-friendly types (strings, numbers, booleans, lists of strings, LangChain4j
 * messages) so they serialize cleanly to Postgres JSONB and render in LangGraph4j Studio.
 * <p>
 * Channels define how a node's partial update is merged into the state:
 * <ul>
 *   <li>{@code messages} and {@code agent_trace} use an <i>appender</i> reducer (updates are appended),</li>
 *   <li>every other key uses the default "last write wins".</li>
 * </ul>
 */
public class SupportState extends MessagesState<ChatMessage> {

    // conversation / identity
    public static final String MESSAGES = MessagesState.MESSAGES;
    public static final String CUSTOMER_ID = "customer_id";
    public static final String CUSTOMER_PROFILE = "customer_profile";
    public static final String CONVERSATION_SUMMARY = "conversation_summary";
    public static final String MEMORIES = "memories";
    public static final String TURN = "turn";                                // monotonic turn counter (idempotency keys)

    // guardrails
    public static final String GUARDRAIL_VERDICT = "guardrail_verdict";      // ALLOW | BLOCK
    public static final String GUARDRAIL_REASON = "guardrail_reason";

    // routing
    public static final String ROUTE = "route";
    public static final String ROUTE_REASON = "route_reason";

    // agentic RAG
    public static final String RAG_QUERY = "rag_query";
    public static final String RAG_ATTEMPTS = "rag_attempts";
    public static final String DOCUMENTS = "documents";
    public static final String RAG_OUTCOME = "rag_outcome";                  // RELEVANT | REWRITE | GIVE_UP

    // refund workflow + human in the loop
    public static final String REFUND_ORDER_ID = "refund_order_id";
    public static final String REFUND_AMOUNT = "refund_amount";
    public static final String REFUND_REASON = "refund_reason";
    public static final String REFUND_DECISION = "refund_decision";          // AUTO_APPROVE | NEEDS_APPROVAL | INELIGIBLE | NEEDS_INFO
    public static final String REFUND_POLICY_NOTE = "refund_policy_note";
    public static final String APPROVAL_DECISION = "approval_decision";      // APPROVED | REJECTED (set by a human)
    public static final String APPROVAL_NOTE = "approval_note";
    public static final String APPROVER = "approver";

    // escalation
    public static final String TICKET_ID = "ticket_id";

    // response drafting + reflection
    public static final String EVIDENCE = "evidence";                        // facts the draft must be grounded in
    public static final String DRAFT = "draft";
    public static final String CRITIC_VERDICT = "critic_verdict";            // PASS | REVISE
    public static final String CRITIQUE = "critique";
    public static final String REVISIONS = "revisions";
    public static final String FINAL_ANSWER = "final_answer";

    // observability-friendly audit trail of what each node decided this turn
    public static final String AGENT_TRACE = "agent_trace";

    /** Keys that describe the current turn only and are reset when a new user message arrives. */
    public static final List<String> TURN_SCOPED_KEYS = List.of(
            GUARDRAIL_VERDICT, GUARDRAIL_REASON, ROUTE, ROUTE_REASON,
            RAG_QUERY, RAG_ATTEMPTS, DOCUMENTS, RAG_OUTCOME,
            REFUND_ORDER_ID, REFUND_AMOUNT, REFUND_REASON, REFUND_DECISION, REFUND_POLICY_NOTE,
            APPROVAL_DECISION, APPROVAL_NOTE, APPROVER, TICKET_ID,
            EVIDENCE, DRAFT, CRITIC_VERDICT, CRITIQUE, REVISIONS, FINAL_ANSWER, MEMORIES, AGENT_TRACE);

    public static final Map<String, Channel<?>> SCHEMA;

    static {
        var schema = new HashMap<String, Channel<?>>();
        // "WithDuplicate": a user may legitimately send the same text twice ("yes"), so don't de-duplicate
        schema.put(MESSAGES, Channels.appenderWithDuplicate(ArrayList::new));
        schema.put(AGENT_TRACE, Channels.appenderWithDuplicate(ArrayList::new));
        SCHEMA = Map.copyOf(schema);
    }

    public SupportState(Map<String, Object> initData) {
        super(initData);
    }

    // ------------------------------------------------------------------ typed accessors

    public String customerId() {
        return this.<String>value(CUSTOMER_ID).orElseThrow(() -> new IllegalStateException("customer_id missing"));
    }

    public Optional<String> str(String key) {
        return this.<Object>value(key).map(Object::toString).filter(s -> !s.isBlank());
    }

    public int integer(String key) {
        return this.<Object>value(key)
                .map(v -> v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString()))
                .orElse(0);
    }

    public Optional<Double> decimal(String key) {
        return this.<Object>value(key)
                .map(v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString()));
    }

    public List<String> strings(String key) {
        return this.<List<?>>value(key)
                .map(list -> list.stream().map(Object::toString).toList())
                .orElseGet(List::of);
    }

    /** The text of the most recent user message (the request being handled in this turn). */
    public String lastUserText() {
        var msgs = messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof UserMessage um && um.hasSingleText()) {
                return um.singleText();
            }
        }
        return "";
    }

    /**
     * A compact transcript of recent turns, used as context in prompts. Older history is represented by the
     * running {@link #CONVERSATION_SUMMARY} instead (context-window management).
     */
    public String recentTranscript(int maxMessages) {
        var msgs = messages();
        var from = Math.max(0, msgs.size() - maxMessages);
        var sb = new StringBuilder();
        str(CONVERSATION_SUMMARY).ifPresent(s -> sb.append("[Summary of earlier conversation] ").append(s).append('\n'));
        for (var m : msgs.subList(from, msgs.size())) {
            if (m instanceof UserMessage um && um.hasSingleText()) {
                sb.append("Customer: ").append(um.singleText()).append('\n');
            } else if (m instanceof AiMessage ai && ai.text() != null) {
                sb.append("Agent: ").append(ai.text()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Convenience for building node results that also append an audit trail entry. */
    public static Map<String, Object> update(String traceEntry, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be key/value pairs");
        }
        var map = new HashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        map.put(AGENT_TRACE, List.of(traceEntry));
        return map;
    }

    /** Values for {@link #TURN_SCOPED_KEYS} that clear them through their channel ({@link AgentState#MARK_FOR_RESET}). */
    public static Map<String, Object> resetTurn() {
        var map = new HashMap<String, Object>();
        TURN_SCOPED_KEYS.forEach(k -> map.put(k, AgentState.MARK_FOR_RESET));
        return map;
    }
}
