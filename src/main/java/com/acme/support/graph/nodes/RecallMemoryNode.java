package com.acme.support.graph.nodes;

import com.acme.support.graph.SupportState;
import com.acme.support.memory.LongTermMemoryService;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Parallel branch 2 of the context fan-out: semantic recall of long-term memories relevant to this message. */
@Component
public class RecallMemoryNode implements NodeActionWithConfig<SupportState> {

    private final LongTermMemoryService memory;

    public RecallMemoryNode(LongTermMemoryService memory) {
        this.memory = memory;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        var recalled = memory.recall(state.customerId(), state.lastUserText());
        return update("recall_memory: %d fact(s)".formatted(recalled.size()), MEMORIES, recalled);
    }
}
