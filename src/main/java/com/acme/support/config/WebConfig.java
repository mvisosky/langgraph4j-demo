package com.acme.support.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the chat frontend (static files in {@code src/main/resources/static/chat}) at
 * <a href="http://localhost:8080/chat">http://localhost:8080/chat</a>. The root path belongs to LangGraph4j Studio.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/chat").setViewName("forward:/chat/index.html");
        registry.addViewController("/chat/").setViewName("forward:/chat/index.html");
    }
}
