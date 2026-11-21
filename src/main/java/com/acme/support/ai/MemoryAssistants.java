package com.acme.support.ai;

import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Memory management: long-term fact extraction and short-term context compaction.
 */
public final class MemoryAssistants {

    private MemoryAssistants() {}

    /** Extracts durable facts about the customer worth remembering across conversations (semantic memory). */
    public interface FactExtractor {
        record Facts(@Description("new durable facts, each a short third-person sentence; empty if none") List<String> facts) {}

        @SystemMessage("""
                You maintain long-term memory about a customer for a retail support team. From the latest exchange,
                extract only NEW, DURABLE facts that would help in future conversations: preferences (sizes,
                activities, preferred contact or shipping), recurring problems, important life context they
                volunteered, and commitments made to them. Do NOT store: one-off order questions, anything
                already known, payment details, passwords, or sensitive personal data (health, religion, etc.).
                """)
        @UserMessage("""
                Already known:
                {{known}}

                Latest exchange:
                {{exchange}}
                """)
        Facts extract(@V("known") String knownFacts, @V("exchange") String exchange);
    }

    /** Folds old turns into a running summary so prompts stay small as the conversation grows. */
    public interface ConversationSummarizer {
        @SystemMessage("""
                Update the running summary of a customer-support conversation. Keep order ids, amounts, decisions,
                open questions and promises made. Max 120 words. Output only the summary.
                """)
        @UserMessage("""
                Previous summary: {{summary}}

                Older messages to fold in:
                {{transcript}}
                """)
        String summarize(@V("summary") String previousSummary, @V("transcript") String transcript);
    }
}
