package com.acme.support.domain;

import com.acme.support.config.AgentProperties;
import com.acme.support.domain.Domain.Customer;
import com.acme.support.domain.Domain.Tier;
import com.acme.support.domain.SupportRepository.IdentityChallenge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class IdentityServiceTest {

    static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    static final String THREAD = "t-1";
    static final Customer BOB = new Customer("C-1002", "Bob Nguyen", "bob.nguyen@example.com", Tier.STANDARD);

    SupportRepository repository = mock(SupportRepository.class);
    IdentityCodeSender sender = mock(IdentityCodeSender.class);
    IdentityService service;

    @BeforeEach
    void setUp() {
        service = new IdentityService(repository, sender, new AgentProperties.Identity(Duration.ofMinutes(10), 3, 3, true),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.challenge(THREAD)).thenReturn(Optional.empty());
        when(repository.customerByEmail("bob.nguyen@example.com")).thenReturn(Optional.of(BOB));
        when(repository.customerByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(repository.customer("C-1002")).thenReturn(Optional.of(BOB));
        when(repository.bindConversation(THREAD, "C-1002")).thenReturn(true);
    }

    private IdentityChallenge active(String code, int attempts, int issued, Duration validFor) {
        return new IdentityChallenge("C-1002", IdentityService.hash(THREAD, code),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plus(validFor), attempts, issued);
    }

    @Test
    void extractsEmailAndSixDigitCode() {
        assertThat(IdentityService.extractEmail("hi, I'm Bob: Bob.Nguyen@Example.com thanks")).contains("Bob.Nguyen@Example.com");
        assertThat(IdentityService.extractEmail("no email here")).isEmpty();
        assertThat(IdentityService.extractCode("my code is 123456.")).contains("123456");
        assertThat(IdentityService.extractCode("order 1234567 and A-5003")).isEmpty();   // not exactly six digits
    }

    @Test
    void knownEmailIssuesACodeAndSendsIt() {
        var outcome = service.handle(THREAD, "my email is bob.nguyen@example.com");

        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(BOB), code.capture());
        assertThat(code.getValue()).matches("\\d{6}");
        verify(repository).saveChallenge(eq(THREAD), eq("C-1002"), eq(IdentityService.hash(THREAD, code.getValue())), any());
        assertThat(outcome.customerId()).isNull();
        // the reply never echoes the email or the code
        assertThat(outcome.reply()).doesNotContain("bob.nguyen").doesNotContain(code.getValue());
    }

    @Test
    void unknownEmailGetsTheSameReplyAndSendsNothing() {
        var known = service.handle(THREAD, "bob.nguyen@example.com");
        var unknown = service.handle(THREAD, "nobody@example.com");

        assertThat(unknown.reply()).isEqualTo(known.reply());
        verify(sender, times(1)).send(any(), anyString());                        // only for Bob
        verify(repository).saveChallenge(eq(THREAD), isNull(), anyString(), any());   // decoy, can never verify
    }

    @Test
    void decoyChallengeNeverVerifiesEvenWithAMatchingCode() {
        var decoy = new IdentityChallenge(null, IdentityService.hash(THREAD, "111111"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5), 0, 1);
        when(repository.challenge(THREAD)).thenReturn(Optional.of(decoy));

        var outcome = service.handle(THREAD, "111111");

        assertThat(outcome.customerId()).isNull();
        verify(repository, never()).bindConversation(any(), any());
        verify(repository).recordFailedAttempt(THREAD);
    }

    @Test
    void correctCodeBindsTheConversationToTheCustomer() {
        when(repository.challenge(THREAD)).thenReturn(Optional.of(active("482913", 0, 1, Duration.ofMinutes(5))));

        var outcome = service.handle(THREAD, "it's 482913");

        assertThat(outcome.customerId()).isEqualTo("C-1002");
        assertThat(outcome.reply()).isEqualTo("Welcome back Bob! How can I help you today?");
        verify(repository).bindConversation(THREAD, "C-1002");
        verify(repository).deleteChallenge(THREAD);
    }

    @Test
    void wrongCodeIsCountedAndEventuallyInvalidatesTheChallenge() {
        when(repository.challenge(THREAD)).thenReturn(Optional.of(active("482913", 0, 1, Duration.ofMinutes(5))));
        assertThat(service.handle(THREAD, "000000").customerId()).isNull();
        verify(repository).recordFailedAttempt(THREAD);
        verify(repository, never()).invalidateChallenge(THREAD);

        when(repository.challenge(THREAD)).thenReturn(Optional.of(active("482913", 2, 1, Duration.ofMinutes(5))));
        var locked = service.handle(THREAD, "000000");
        assertThat(locked.customerId()).isNull();
        assertThat(locked.reply()).contains("out of attempts");
        verify(repository).invalidateChallenge(THREAD);
        verify(repository, never()).bindConversation(any(), any());
    }

    @Test
    void expiredCodeIsRejectedEvenIfCorrect() {
        when(repository.challenge(THREAD)).thenReturn(Optional.of(active("482913", 0, 1, Duration.ofMinutes(-1))));

        var outcome = service.handle(THREAD, "482913");

        assertThat(outcome.customerId()).isNull();
        assertThat(outcome.reply()).contains("expired");
        verify(repository).invalidateChallenge(THREAD);
        verify(repository, never()).bindConversation(any(), any());
    }

    @Test
    void codeIsBoundToTheConversationItWasIssuedFor() {
        // a hash issued for another thread must not verify here
        var other = new IdentityChallenge("C-1002", IdentityService.hash("other-thread", "482913"),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(5), 0, 1);
        when(repository.challenge(THREAD)).thenReturn(Optional.of(other));

        assertThat(service.handle(THREAD, "482913").customerId()).isNull();
    }

    @Test
    void codeRequestsAreCappedPerConversation() {
        when(repository.challenge(THREAD)).thenReturn(Optional.of(
                new IdentityChallenge("C-1002", null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), 0, 3)));

        var outcome = service.handle(THREAD, "bob.nguyen@example.com");

        assertThat(outcome.reply()).contains("start a new conversation");
        verify(sender, never()).send(any(), anyString());
    }

    @Test
    void asksForEmailWhenNothingRecognisable() {
        assertThat(service.handle(THREAD, "hello").reply()).contains("email address");
        assertThat(service.handle(THREAD, "123456").reply()).contains("email address");   // code without a challenge
        verifyNoInteractions(sender);
    }
}
