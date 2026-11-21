package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * The LLM steps of the corrective (agentic) RAG sub-graph.
 */
public final class RagAssistants {

    private RagAssistants() {}

    /** Turns a conversational question into a standalone search query; on retries, tries a different angle. */
    public interface QueryRewriter {
        @SystemMessage("""
                You write search queries for a retailer's policy knowledge base (returns, refunds, shipping,
                warranty, loyalty program, payments, product care). Output ONLY the query: a short standalone
                sentence or keyword list, resolving pronouns from the conversation. No quotes, no explanation.
                """)
        @UserMessage("""
                Conversation:
                {{transcript}}

                Question to answer: {{question}}
                Previous query (returned nothing relevant, try a different formulation or synonyms): {{previous}}
                """)
        String rewrite(@V("transcript") String transcript, @V("question") String question, @V("previous") String previousQuery);
    }

    /** Grades each retrieved chunk for relevance (the "corrective" part of corrective RAG). */
    public interface DocumentGrader {
        record Grade(@Description("indexes (as shown in brackets) of the documents that help answer the question") List<Integer> relevant,
                     @Description("one short sentence") String rationale) {}

        @SystemMessage("""
                You grade search results. A document is relevant if it contains information that helps answer the
                question, even partially. Be strict: same topic but no useful facts is NOT relevant.
                """)
        @UserMessage("""
                Question: {{question}}

                Documents:
                {{documents}}
                """)
        Grade grade(@V("question") String question, @V("documents") String numberedDocuments);
    }

    /** Writes the grounded answer (smart model). */
    public interface GroundedAnswerWriter {
        @SystemMessage("""
                You are Acme Outfitters' friendly support assistant. Answer the customer's question using ONLY the
                policy excerpts provided. Personalise using the customer's tier when the policy differs by tier.
                Cite the policy name in parentheses, e.g. (Returns and Refunds Policy). If the excerpts don't fully
                answer the question, say what you can and offer to connect them with a specialist.
                Keep it under 150 words. Never invent policy details, prices or timelines.
                """)
        @UserMessage("""
                Customer profile: {{profile}}
                What we remember about this customer: {{memories}}
                Recent conversation:
                {{transcript}}

                Policy excerpts:
                {{documents}}

                Question: {{question}}
                """)
        String answer(@V("profile") String profile,
                      @V("memories") String memories,
                      @V("transcript") String transcript,
                      @V("documents") String documents,
                      @V("question") String question);
    }
}
