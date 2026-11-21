package com.acme.support.graph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class SupportGraphConfig {

    /** Virtual threads for graph execution and parallel branches (LLM calls are I/O bound). */
    @Bean(destroyMethod = "close")
    ExecutorService graphExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * The production compilation of the graph:
     * <ul>
     *   <li>Postgres checkpoints → durable short-term memory and resumable runs</li>
     *   <li>{@code interruptBefore(human_approval)} → the run pauses for a human decision</li>
     *   <li>{@code releaseThread(false)} → the thread's state is kept after each turn, so the next message on the
     *       same thread continues the conversation (multi-turn chat)</li>
     * </ul>
     */
    @Bean
    CompiledGraph<SupportState> supportGraph(SupportGraphFactory factory, BaseCheckpointSaver checkpointSaver) throws Exception {
        return factory.build().compile(CompileConfig.builder()
                .checkpointSaver(checkpointSaver)
                .interruptBefore(Nodes.HUMAN_APPROVAL)
                .releaseThread(false)
                .recursionLimit(40)
                .build());
    }
}
