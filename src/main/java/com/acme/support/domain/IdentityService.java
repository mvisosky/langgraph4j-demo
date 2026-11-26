package com.acme.support.domain;

import com.acme.support.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Policy-as-code for establishing who a customer is from inside a chat: email + one-time code.
 * Fully deterministic - the LLM never sees the email or the code and can never grant identity.
 *
 * <p>Anti-abuse rules: the reply to an email is identical whether or not it matches an account (no account
 * enumeration), codes expire, wrong guesses are capped, and code requests per conversation are capped (counted
 * for unknown emails too, so the cap itself leaks nothing).
 */
@Service
public class IdentityService {

    /** reply is shown to the customer; customerId is non-null only when verification just succeeded. */
    public record Outcome(String reply, String customerId) {}

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final Pattern CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SupportRepository repository;
    private final IdentityCodeSender sender;
    private final AgentProperties.Identity cfg;
    private final Clock clock;

    @Autowired
    public IdentityService(SupportRepository repository, IdentityCodeSender sender, AgentProperties props) {
        this(repository, sender, props.identity(), Clock.systemUTC());
    }

    IdentityService(SupportRepository repository, IdentityCodeSender sender, AgentProperties.Identity cfg, Clock clock) {
        this.repository = repository;
        this.sender = sender;
        this.cfg = cfg;
        this.clock = clock;
    }

    public static Optional<String> extractEmail(String text) {
        var m = EMAIL.matcher(text == null ? "" : text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    public static Optional<String> extractCode(String text) {
        var m = CODE.matcher(text == null ? "" : text);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /** Handles one message of an anonymous conversation. */
    public Outcome handle(String threadId, String message) {
        var challenge = repository.challenge(threadId);
        boolean codeActive = challenge.map(c -> c.codeHash() != null).orElse(false);
        var code = extractCode(message);
        var email = extractEmail(message);

        if (code.isPresent() && codeActive) {
            return verify(threadId, challenge.get(), code.get());
        }
        if (email.isPresent()) {
            return issue(threadId, email.get(), challenge.map(c -> c.issuedCount()).orElse(0));
        }
        if (code.isPresent()) {
            return new Outcome("I don't have an active verification code for this conversation. "
                    + "Please tell me the email address on your account and I'll send one.", null);
        }
        return new Outcome("I'd be happy to help! First I need to verify who you are. "
                + "What's the email address on your account?", null);
    }

    private Outcome issue(String threadId, String email, int issuedSoFar) {
        if (issuedSoFar >= cfg.maxCodes()) {
            return new Outcome("I've already sent several codes for this conversation. For your security, please "
                    + "start a new conversation or contact our support team to continue.", null);
        }
        var expires = OffsetDateTime.now(clock).plus(cfg.codeTtl());
        var customer = repository.customerByEmail(email);
        if (customer.isPresent()) {
            String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
            repository.saveChallenge(threadId, customer.get().id(), hash(threadId, code), expires);
            sender.send(customer.get(), code);
        } else {
            // Unknown email: store a decoy (no customer, so it can never verify) so every later reply, attempt
            // counter and cap behaves exactly as for a real account. Nothing is sent.
            String decoy = "%06d".formatted(RANDOM.nextInt(1_000_000));
            repository.saveChallenge(threadId, null, hash(threadId, decoy), expires);
        }
        return new Outcome("Thanks. If that email address is on file, I've sent a 6-digit verification code to it. "
                + "Please reply with the code to continue.", null);
    }

    private Outcome verify(String threadId, SupportRepository.IdentityChallenge challenge, String code) {
        if (challenge.expiresAt().isBefore(OffsetDateTime.now(clock))) {
            repository.invalidateChallenge(threadId);
            return new Outcome("That code has expired. Please tell me your account email again and I'll send a new one.", null);
        }
        boolean match = challenge.customerId() != null && MessageDigest.isEqual(
                hash(threadId, code).getBytes(StandardCharsets.UTF_8),
                challenge.codeHash().getBytes(StandardCharsets.UTF_8));
        if (match) {
            var customer = repository.customer(challenge.customerId()).orElse(null);
            if (customer != null && repository.bindConversation(threadId, customer.id())) {
                repository.deleteChallenge(threadId);
                return new Outcome("Welcome back %s! How can I help you today?".formatted(customer.firstName()),
                        customer.id());
            }
            repository.invalidateChallenge(threadId);
            return new Outcome("I couldn't complete verification. Please start a new conversation.", null);
        }
        repository.recordFailedAttempt(threadId);
        if (challenge.attempts() + 1 >= cfg.maxAttempts()) {
            repository.invalidateChallenge(threadId);
            return new Outcome("That code isn't right and you're out of attempts. Please tell me your account email "
                    + "again to get a new code.", null);
        }
        return new Outcome("That code doesn't match. Please check it and try again.", null);
    }

    /** SHA-256 over thread id + code: a code is only valid in the conversation it was issued for. */
    static String hash(String threadId, String code) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((threadId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
