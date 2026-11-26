package com.acme.support.domain;

import com.acme.support.domain.Domain.Customer;
import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.Domain.OrderItem;
import com.acme.support.domain.Domain.Tier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Data access for customers, orders, refunds and tickets. Every order query is scoped by customer id:
 * the agent can never read another customer's data, whatever the LLM asks for.
 */
@Repository
public class SupportRepository {

    private final JdbcClient jdbc;

    public SupportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> customer(String customerId) {
        return jdbc.sql("SELECT id, name, email, tier FROM customers WHERE id = :id")
                .param("id", customerId)
                .query((rs, n) -> new Customer(rs.getString("id"), rs.getString("name"),
                        rs.getString("email"), Tier.valueOf(rs.getString("tier"))))
                .optional();
    }

    public Optional<Customer> customerByEmail(String email) {
        return jdbc.sql("SELECT id, name, email, tier FROM customers WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query((rs, n) -> new Customer(rs.getString("id"), rs.getString("name"),
                        rs.getString("email"), Tier.valueOf(rs.getString("tier"))))
                .optional();
    }

    /** Binds an anonymous conversation to a verified customer. Only ever sets an unowned thread. */
    public boolean bindConversation(String threadId, String customerId) {
        return jdbc.sql("UPDATE conversations SET customer_id = :c WHERE thread_id = :t AND customer_id IS NULL")
                .param("c", customerId).param("t", threadId).update() == 1;
    }

    // ------------------------------------------------------------------ identity challenges

    /** An in-chat verification challenge; {@code codeHash} is null when no code is currently active. */
    public record IdentityChallenge(String customerId, String codeHash, OffsetDateTime expiresAt,
                                    int attempts, int issuedCount) {}

    public Optional<IdentityChallenge> challenge(String threadId) {
        return jdbc.sql("SELECT customer_id, code_hash, expires_at, attempts, issued_count "
                        + "FROM identity_challenges WHERE thread_id = :t")
                .param("t", threadId)
                .query((rs, n) -> new IdentityChallenge(rs.getString("customer_id"), rs.getString("code_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getInt("attempts"),
                        rs.getInt("issued_count")))
                .optional();
    }

    /** Stores a new code (or none, for an unknown email), resetting attempts and counting the issuance. */
    public void saveChallenge(String threadId, String customerId, String codeHash, OffsetDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO identity_challenges (thread_id, customer_id, code_hash, expires_at)
                        VALUES (:t, :c, :h, :e)
                        ON CONFLICT (thread_id) DO UPDATE SET customer_id = :c, code_hash = :h, expires_at = :e,
                            attempts = 0, issued_count = identity_challenges.issued_count + 1""")
                .param("t", threadId).param("c", customerId).param("h", codeHash).param("e", expiresAt)
                .update();
    }

    public void recordFailedAttempt(String threadId) {
        jdbc.sql("UPDATE identity_challenges SET attempts = attempts + 1 WHERE thread_id = :t")
                .param("t", threadId).update();
    }

    /** Invalidates the active code but keeps the row so the issue cap still applies. */
    public void invalidateChallenge(String threadId) {
        jdbc.sql("UPDATE identity_challenges SET code_hash = NULL WHERE thread_id = :t")
                .param("t", threadId).update();
    }

    public void deleteChallenge(String threadId) {
        jdbc.sql("DELETE FROM identity_challenges WHERE thread_id = :t").param("t", threadId).update();
    }

    public List<Order> orders(String customerId) {
        return jdbc.sql("SELECT * FROM orders WHERE customer_id = :cid ORDER BY placed_at DESC")
                .param("cid", customerId)
                .query((rs, n) -> mapOrder(rs, items(rs.getString("id"))))
                .list();
    }

    public Optional<Order> order(String customerId, String orderId) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :oid AND customer_id = :cid")
                .param("oid", orderId == null ? "" : orderId.trim().toUpperCase())
                .param("cid", customerId)
                .query((rs, n) -> mapOrder(rs, items(rs.getString("id"))))
                .optional();
    }

    public BigDecimal refundedSoFar(String orderId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE order_id = :oid AND status = 'ISSUED'")
                .param("oid", orderId)
                .query(BigDecimal.class)
                .single();
    }

    /**
     * Records a refund decision idempotently: a second call with the same key returns the existing refund id
     * instead of creating another one (graph retries, replays from a checkpoint, double-clicked approvals).
     */
    public long recordRefund(String idempotencyKey, String orderId, String customerId, BigDecimal amount,
                             String reason, String status, String decidedBy, String threadId) {
        jdbc.sql("""
                        INSERT INTO refunds (idempotency_key, order_id, customer_id, amount, reason, status, decided_by, thread_id)
                        VALUES (:key, :oid, :cid, :amount, :reason, :status, :by, :tid)
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("key", idempotencyKey)
                .param("oid", orderId).param("cid", customerId).param("amount", amount)
                .param("reason", reason).param("status", status).param("by", decidedBy).param("tid", threadId)
                .update();
        return jdbc.sql("SELECT id FROM refunds WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
    }

    public long createTicket(String customerId, String threadId, String priority, String category, String summary) {
        return jdbc.sql("""
                        INSERT INTO tickets (customer_id, thread_id, priority, category, summary)
                        VALUES (:cid, :tid, :priority, :category, :summary) RETURNING id""")
                .param("cid", customerId).param("tid", threadId).param("priority", priority)
                .param("category", category).param("summary", summary)
                .query(Long.class)
                .single();
    }

    private List<OrderItem> items(String orderId) {
        return jdbc.sql("SELECT * FROM order_items WHERE order_id = :oid ORDER BY sku")
                .param("oid", orderId)
                .query((rs, n) -> new OrderItem(rs.getString("sku"), rs.getString("name"), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBoolean("final_sale")))
                .list();
    }

    private static Order mapOrder(ResultSet rs, List<OrderItem> items) throws SQLException {
        return new Order(rs.getString("id"), rs.getString("customer_id"), rs.getString("status"),
                rs.getBigDecimal("total"),
                rs.getObject("placed_at", OffsetDateTime.class),
                rs.getObject("delivered_at", OffsetDateTime.class),
                rs.getString("carrier"), rs.getString("tracking_number"), items);
    }
}
