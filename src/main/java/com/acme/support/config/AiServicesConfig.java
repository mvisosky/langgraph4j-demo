package com.acme.support.config;

import com.acme.support.ai.InputGuard;
import com.acme.support.ai.IntentRouter;
import com.acme.support.ai.MemoryAssistants;
import com.acme.support.ai.RagAssistants;
import com.acme.support.ai.ReflectionAssistants;
import com.acme.support.ai.RefundAssistants;
import com.acme.support.ai.ResponseAssistants;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j AiServices: each interface is a typed "LLM function" with its own prompt and structured
 * output, bound to the model tier that suits the task (cost/latency-aware model routing).
 */
@Configuration(proxyBeanMethods = false)
public class AiServicesConfig {

    // ---------------------------------------------------------------- fast tier (classification / extraction)

    @Bean
    InputGuard inputGuard(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(InputGuard.class, fast);
    }

    @Bean
    IntentRouter intentRouter(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(IntentRouter.class, fast);
    }

    @Bean
    RagAssistants.QueryRewriter queryRewriter(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(RagAssistants.QueryRewriter.class, fast);
    }

    @Bean
    RagAssistants.DocumentGrader documentGrader(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(RagAssistants.DocumentGrader.class, fast);
    }

    @Bean
    RefundAssistants.RefundRequestExtractor refundRequestExtractor(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(RefundAssistants.RefundRequestExtractor.class, fast);
    }

    @Bean
    MemoryAssistants.FactExtractor factExtractor(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(MemoryAssistants.FactExtractor.class, fast);
    }

    @Bean
    MemoryAssistants.ConversationSummarizer conversationSummarizer(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(MemoryAssistants.ConversationSummarizer.class, fast);
    }

    @Bean
    ResponseAssistants.SmallTalkResponder smallTalkResponder(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(ResponseAssistants.SmallTalkResponder.class, fast);
    }

    @Bean
    ResponseAssistants.EscalationTriage escalationTriage(@Qualifier("fast") ChatModel fast) {
        return AiServices.create(ResponseAssistants.EscalationTriage.class, fast);
    }

    // ---------------------------------------------------------------- smart tier (generation / judgement)

    @Bean
    RagAssistants.GroundedAnswerWriter groundedAnswerWriter(@Qualifier("smart") ChatModel smart) {
        return AiServices.create(RagAssistants.GroundedAnswerWriter.class, smart);
    }

    @Bean
    RefundAssistants.RefundResponder refundResponder(@Qualifier("smart") ChatModel smart) {
        return AiServices.create(RefundAssistants.RefundResponder.class, smart);
    }

    @Bean
    ReflectionAssistants.Critic critic(@Qualifier("smart") ChatModel smart) {
        return AiServices.create(ReflectionAssistants.Critic.class, smart);
    }

    @Bean
    ReflectionAssistants.Reviser reviser(@Qualifier("smart") ChatModel smart) {
        return AiServices.create(ReflectionAssistants.Reviser.class, smart);
    }
}
