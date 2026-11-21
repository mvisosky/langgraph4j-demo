package com.acme.support.config;

import com.acme.support.graph.Nodes;
import com.acme.support.graph.SupportGraphFactory;
import dev.langchain4j.data.message.UserMessage;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.studio.LangGraphStudioServer;
import org.bsc.langgraph4j.studio.springboot.LangGraphStudioConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * LangGraph4j Studio: a visual playground served by this application at
 * <a href="http://localhost:8080/?instance=support">http://localhost:8080/?instance=support</a>.
 * <p>
 * It renders the graph (including the flattened RAG sub-graph and the parallel branch), lets you run it
 * step by step, inspect and edit the state at each checkpoint, and resume after the {@code human_approval}
 * interrupt - set {@code approval_decision} to APPROVED/REJECTED and {@code approver} in the state editor.
 * <p>
 * Studio runs use an in-memory checkpoint saver so playground threads don't mix with real conversations;
 * they do hit the real database, LLM and tools. Inputs: {@code customer_id} (e.g. C-1002) and {@code messages}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.studio", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StudioConfig extends LangGraphStudioConfig {

    private final SupportGraphFactory factory;

    public StudioConfig(SupportGraphFactory factory) {
        this.factory = factory;
    }

    @Override
    public Map<String, LangGraphStudioServer.Instance> instanceMap() {
        try {
            var instance = LangGraphStudioServer.Instance.builder()
                    .title("Acme Support Agent")
                    .graph(factory.build())
                    .compileConfig(CompileConfig.builder()
                            .checkpointSaver(new MemorySaver())
                            .interruptBefore(Nodes.HUMAN_APPROVAL)
                            .releaseThread(false)
                            .build())
                    .addInputStringArg("customer_id", true)
                    .addInputStringArg("messages", true, text -> List.of(UserMessage.from(String.valueOf(text))))
                    .build();
            return Map.of("support", instance);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Cannot build graph for Studio", e);
        }
    }
}
