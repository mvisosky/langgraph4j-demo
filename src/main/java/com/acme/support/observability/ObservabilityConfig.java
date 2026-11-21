package com.acme.support.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /** Instrumentation scope for agent-specific spans (graph nodes, LLM calls, tools, retrieval). */
    public static final String SCOPE = "com.acme.support.agent";

    /**
     * Tracer for agent spans. Deliberately not exposed as a {@link Tracer} bean: Spring Boot registers its own
     * OpenTelemetry {@code Tracer} bean for the Micrometer bridge and we don't want to replace it.
     */
    public static Tracer agentTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(SCOPE);
    }

    /** Hands the Spring-managed OpenTelemetry SDK to the Logback appender declared in logback-spring.xml. */
    @Bean
    InitializingBean openTelemetryLogInstaller(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
