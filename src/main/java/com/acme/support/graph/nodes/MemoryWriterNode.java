package com.acme.support.graph.nodes;

import com.acme.support.ai.MemoryAssistants;
import com.acme.support.config.AgentProperties;
import com.acme.support.graph.SupportState;
import com.acme.support.memory.LongTermMemoryService;
import com.acme.support.observability.AgentMetrics;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/**
 * End-of-turn memory management:
 * <ul>
 *   <li><b>Long-term memory write</b> – extracts durable facts ("prefers EU size 42 boots") and stores them in
 *       the vector memory, so they are recalled in future conversations.</li>
 *   <li><b>Short-term memory compaction</b> – once the thread exceeds a threshold, older messages are folded into
 *       a running summary and dropped from the message list (using the appender channel's
 *       {@code ReplaceAllWith}), keeping prompts and checkpoints small.</li>
 * </ul>
 * Memory is best-effort: failures are logged and never fail the customer's turn.
 */
@Component
public class MemoryWriterNode implements NodeActionWithConfig<SupportState> {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriterNode.class);
    private static final int MAX_FACTS_PER_TURN = 3;

    private final MemoryAssistants.FactExtractor extractor;
    private final MemoryAssistants.ConversationSummarizer summarizer;
    private final LongTermMemoryService memory;
    private final AgentProperties.Memory cfg;
    private final AgentMetrics metrics;

    public MemoryWriterNode(MemoryAssistants.FactExtractor extractor,
                            MemoryAssistants.ConversationSummarizer summarizer,
                            LongTermMemoryService memory,
                            AgentProperties props,
                            AgentMetrics metrics) {
        this.extractor = extractor;
        this.summarizer = summarizer;
        this.memory = memory;
        this.cfg = props.memory();
        this.metrics = metrics;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        var result = new HashMap<String, Object>();
        var trace = new ArrayList<String>();

        // 1. long-term semantic memory
        try {
            String exchange = "Customer: %s\nAgent: %s".formatted(state.lastUserText(), state.str(FINAL_ANSWER).orElse(""));
            var facts = extractor.extract(String.join("\n", state.strings(MEMORIES)), exchange).facts();
            var newFacts = facts == null ? List.<String>of()
                    : facts.stream().filter(f -> f != null && !f.isBlank()).limit(MAX_FACTS_PER_TURN).toList();
            memory.remember(state.customerId(), config.threadId().orElse("default"), newFacts);
            metrics.memoryWritten(newFacts.size());
            trace.add("memory_writer: stored %d fact(s) %s".formatted(newFacts.size(), newFacts));
        } catch (RuntimeException e) {
            log.warn("Long-term memory extraction failed: {}", e.toString());
            trace.add("memory_writer: fact extraction failed");
        }

        // 2. short-term memory compaction
        List<ChatMessage> messages = state.messages();
        if (messages.size() > cfg.summarizeAfterMessages()) {
            try {
                int keep = cfg.keepRecentMessages();
                var older = messages.subList(0, messages.size() - keep);
                var recent = new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));
                String summary = summarizer.summarize(state.str(CONVERSATION_SUMMARY).orElse("(none)"), transcript(older));
                result.put(CONVERSATION_SUMMARY, summary);
                result.put(MESSAGES, AppenderChannel.ReplaceAllWith.of(recent));
                trace.add("memory_writer: compacted %d older message(s) into the summary".formatted(older.size()));
            } catch (RuntimeException e) {
                log.warn("Conversation summarization failed: {}", e.toString());
            }
        }

        result.put(AGENT_TRACE, trace);
        return result;
    }

    private static String transcript(List<ChatMessage> messages) {
        var sb = new StringBuilder();
        for (var m : messages) {
            if (m instanceof UserMessage um && um.hasSingleText()) sb.append("Customer: ").append(um.singleText()).append('\n');
            else if (m instanceof AiMessage ai && ai.text() != null) sb.append("Agent: ").append(ai.text()).append('\n');
        }
        return sb.toString();
    }
}
