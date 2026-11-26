package com.acme.support.domain;

import com.acme.support.config.AgentProperties;
import com.acme.support.domain.Domain.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY: there is no mail infrastructure, so the code is written to the application log when
 * {@code agent.identity.log-code} is true. Replace with a real email/SMS sender in production.
 * The customer's email address is never logged - only the customer id.
 */
@Component
public class LoggingIdentityCodeSender implements IdentityCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingIdentityCodeSender.class);

    private final boolean logCode;

    public LoggingIdentityCodeSender(AgentProperties props) {
        this.logCode = props.identity().logCode();
    }

    @Override
    public void send(Customer customer, String code) {
        if (logCode) {
            log.info("[DEMO] verification code for customer {}: {}", customer.id(), code);
        } else {
            log.warn("No IdentityCodeSender configured and agent.identity.log-code=false; code for customer {} not delivered",
                    customer.id());
        }
    }
}
