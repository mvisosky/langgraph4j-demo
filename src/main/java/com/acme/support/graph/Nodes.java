package com.acme.support.graph;

/** Node identifiers of the support graph (and of the knowledge sub-graph). */
public final class Nodes {

    private Nodes() {}

    // main graph
    public static final String START_TURN = "start_turn";
    public static final String INPUT_GUARDRAIL = "input_guardrail";
    public static final String BLOCKED_RESPONSE = "blocked_response";
    public static final String IDENTIFY_CUSTOMER = "identify_customer"; // in-chat email + code verification
    public static final String LOAD_CONTEXT = "load_context";          // fan-out point (parallel branch)
    public static final String LOAD_CUSTOMER = "load_customer";
    public static final String RECALL_MEMORY = "recall_memory";
    public static final String SUPERVISOR = "supervisor";
    public static final String KNOWLEDGE_AGENT = "knowledge_agent";    // sub-graph (corrective RAG)
    public static final String ORDER_AGENT = "order_agent";            // nested prebuilt ReAct agent
    public static final String REFUND_AGENT = "refund_agent";
    public static final String HUMAN_APPROVAL = "human_approval";      // interrupt point (human in the loop)
    public static final String ISSUE_REFUND = "issue_refund";
    public static final String ESCALATION_AGENT = "escalation_agent";
    public static final String SMALLTALK_AGENT = "smalltalk_agent";
    public static final String CRITIC = "critic";
    public static final String REVISER = "reviser";
    public static final String OUTPUT_GUARDRAIL = "output_guardrail";
    public static final String MEMORY_WRITER = "memory_writer";

    // knowledge sub-graph
    public static final String REWRITE_QUERY = "rewrite_query";
    public static final String RETRIEVE = "retrieve";
    public static final String GRADE_DOCUMENTS = "grade_documents";
    public static final String GENERATE_ANSWER = "generate_answer";
    public static final String NO_ANSWER = "no_answer";
}
