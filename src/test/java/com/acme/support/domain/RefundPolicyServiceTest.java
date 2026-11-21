package com.acme.support.domain;

import com.acme.support.config.AgentProperties;
import com.acme.support.domain.Domain.Customer;
import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.Domain.OrderItem;
import com.acme.support.domain.Domain.Tier;
import com.acme.support.domain.RefundPolicyService.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RefundPolicyServiceTest {

    static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    final RefundPolicyService policy = new RefundPolicyService(
            new AgentProperties.Refund(new BigDecimal("100.00"), 30), Clock.fixed(NOW, ZoneOffset.UTC));

    final Customer standard = new Customer("C-1", "Bob N", "b@example.com", Tier.STANDARD);
    final Customer platinum = new Customer("C-2", "Carol D", "c@example.com", Tier.PLATINUM);

    static Order delivered(String id, int daysAgo, OrderItem... items) {
        var total = List.of(items).stream().map(OrderItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        var at = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new Order(id, "C-1", "DELIVERED", total, at.minusDays(daysAgo + 4), at.minusDays(daysAgo),
                "UPS", "1Z", List.of(items));
    }

    static OrderItem item(String sku, String price, boolean finalSale) {
        return new OrderItem(sku, sku, 1, new BigDecimal(price), finalSale);
    }

    @Test
    void smallRefundIsAutoApprovedWithStandardReturnFee() {
        var order = delivered("A-1", 8, item("SOCK", "29.90", false), item("LAMP", "35.00", false));
        var d = policy.evaluate(standard, Optional.of(order), "", false, BigDecimal.ZERO);
        assertThat(d.outcome()).isEqualTo(Outcome.AUTO_APPROVE);
        assertThat(d.amount()).isEqualByComparingTo("57.95");   // 64.90 - 6.95
    }

    @Test
    void largeRefundNeedsHumanApproval() {
        var order = delivered("A-3", 5, item("TENT", "349.99", false));
        var d = policy.evaluate(standard, Optional.of(order), "TENT", false, BigDecimal.ZERO);
        assertThat(d.outcome()).isEqualTo(Outcome.NEEDS_APPROVAL);
        assertThat(d.amount()).isEqualByComparingTo("343.04");
    }

    @Test
    void outsideWindowIsIneligibleButPlatinumGetsSixtyDays() {
        var order = delivered("A-4", 45, item("BOTTLE", "42.50", false));
        assertThat(policy.evaluate(standard, Optional.of(order), "", false, BigDecimal.ZERO).outcome())
                .isEqualTo(Outcome.INELIGIBLE);
        assertThat(policy.evaluate(platinum, Optional.of(order), "", false, BigDecimal.ZERO).outcome())
                .isEqualTo(Outcome.AUTO_APPROVE);
    }

    @Test
    void finalSaleItemIsIneligibleUnlessDamaged() {
        var order = delivered("A-6", 2, item("BOOTS", "160.00", false), item("GLOVES", "50.00", true));
        assertThat(policy.evaluate(platinum, Optional.of(order), "GLOVES", false, BigDecimal.ZERO).outcome())
                .isEqualTo(Outcome.INELIGIBLE);
        var damaged = policy.evaluate(platinum, Optional.of(order), "GLOVES", true, BigDecimal.ZERO);
        assertThat(damaged.outcome()).isEqualTo(Outcome.AUTO_APPROVE);
        assertThat(damaged.amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void lateDamageReportFallsBackToNormalReturnRules() {
        var gold = new Customer("C-3", "Alice M", "a@example.com", Tier.GOLD);
        var order = delivered("A-1", 8, item("LAMP", "35.00", false));
        var d = policy.evaluate(gold, Optional.of(order), "LAMP", true, BigDecimal.ZERO);
        assertThat(d.outcome()).isEqualTo(Outcome.AUTO_APPROVE);
        assertThat(d.amount()).isEqualByComparingTo("35.00");   // Gold: no return fee
    }

    @Test
    void alreadyRefundedAmountIsDeducted() {
        var order = delivered("A-1", 3, item("LAMP", "35.00", false));
        var d = policy.evaluate(platinum, Optional.of(order), "", false, new BigDecimal("35.00"));
        assertThat(d.outcome()).isEqualTo(Outcome.INELIGIBLE);
    }

    @Test
    void unknownOrderNeedsInfo() {
        assertThat(policy.evaluate(standard, Optional.empty(), "", false, BigDecimal.ZERO).outcome())
                .isEqualTo(Outcome.NEEDS_INFO);
    }
}
