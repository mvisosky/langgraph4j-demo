package com.acme.support.domain;

import com.acme.support.domain.Domain.Customer;

/** Delivers a one-time verification code to a customer out of band (email/SMS in a real system). */
public interface IdentityCodeSender {

    void send(Customer customer, String code);
}
