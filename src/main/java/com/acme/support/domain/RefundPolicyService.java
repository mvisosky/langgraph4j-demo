package com.acme.support.domain;

import com.acme.support.config.AgentProperties;
import com.acme.support.domain.Domain.Customer;
import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.Domain.OrderItem;
import com.acme.support.domain.Domain.Tier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Deterministic refund eligibility rules (mirrors knowledge/returns-and-refunds.md).
 * <p>
 * This is a deliberate design choice: the LLM extracts <i>what</i> is being asked; plain code decides
 * <i>whether</i> money moves and whether a human must sign off. Policy-as-code is testable, auditable and
 * immune to prompt injection.
 */
@Service
public class RefundPolicyService {

    public enum Outcome { AUTO_APPROVE, NEEDS_APPROVAL, INELIGIBLE, NEEDS_INFO }

    public record Decision(Outcome outcome, BigDecimal amount, String note) {}

    static final BigDecimal STANDARD_RETURN_FEE = new BigDecimal("6.95");
    static final int DAMAGE_REPORT_DAYS = 7;

    private final AgentProperties.Refund cfg;
    private final Clock clock;

    @Autowired
    public RefundPolicyService(AgentProperties props) {
        this(props.refund(), Clock.systemUTC());
    }

    RefundPolicyService(AgentProperties.Refund cfg, Clock clock) {
        this.cfg = cfg;
        this.clock = clock;
    }

    public Decision evaluate(Customer customer, Optional<Order> maybeOrder, String sku,
                             boolean damagedOrWrong, BigDecimal alreadyRefunded) {
        if (maybeOrder.isEmpty()) {
            return new Decision(Outcome.NEEDS_INFO, BigDecimal.ZERO,
                    "No matching order on this customer's account; ask which order (id like A-5001).");
        }
        var order = maybeOrder.get();

        switch (order.status()) {
            case "PROCESSING" -> {
                return withinLimit(order.total(), "Order is still PROCESSING: free cancellation for the full amount.");
            }
            case "SHIPPED" -> {
                return new Decision(Outcome.INELIGIBLE, BigDecimal.ZERO,
                        "Order has SHIPPED but is not delivered; it cannot be cancelled. The customer can refuse the "
                                + "delivery or return it after it arrives.");
            }
            case "CANCELLED" -> {
                return new Decision(Outcome.INELIGIBLE, BigDecimal.ZERO, "Order was already cancelled.");
            }
            default -> { /* DELIVERED: continue */ }
        }

        Optional<OrderItem> item = (sku == null || sku.isBlank()) ? Optional.empty()
                : order.items().stream().filter(i -> i.sku().equalsIgnoreCase(sku.trim())).findFirst();

        long daysSinceDelivery = order.deliveredAt() == null ? 0
                : Duration.between(order.deliveredAt(), OffsetDateTime.now(clock)).toDays();
        int window = customer.tier() == Tier.PLATINUM ? 60 : cfg.windowDays();

        // Damage reported within 7 days unlocks extra benefits (final-sale override, no return fee).
        // Reported later, it is treated as a normal return; anything beyond that is a warranty matter.
        boolean damageBenefits = damagedOrWrong && daysSinceDelivery <= DAMAGE_REPORT_DAYS;
        if (!damageBenefits) {
            if (daysSinceDelivery > window) {
                return new Decision(Outcome.INELIGIBLE, BigDecimal.ZERO,
                        "Delivered %d days ago; the %s return window is %d days.%s".formatted(
                                daysSinceDelivery, customer.tier(), window,
                                damagedOrWrong ? " A defect can still be covered by a warranty claim." : ""));
            }
            var finalSale = item.map(OrderItem::finalSale)
                    .orElseGet(() -> order.items().stream().allMatch(OrderItem::finalSale));
            if (finalSale) {
                return new Decision(Outcome.INELIGIBLE, BigDecimal.ZERO,
                        "Item is FINAL SALE and damage was not reported within %d days of delivery, so it cannot be refunded.%s"
                                .formatted(DAMAGE_REPORT_DAYS, damagedOrWrong ? " Offer a warranty claim instead." : ""));
            }
        }

        // amount: the item, or every non-final-sale line of the order, minus what was already refunded
        BigDecimal gross = item.map(OrderItem::lineTotal).orElseGet(() -> order.items().stream()
                .filter(i -> damageBenefits || !i.finalSale())
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal remaining = order.total().subtract(alreadyRefunded);
        BigDecimal amount = gross.min(remaining);

        String note = "Delivered %d days ago, within the %d-day window.".formatted(daysSinceDelivery, window);
        if (customer.tier() == Tier.STANDARD && !damageBenefits) {
            amount = amount.subtract(STANDARD_RETURN_FEE);
            note += " $%s return-shipping fee deducted (Standard tier).".formatted(STANDARD_RETURN_FEE);
        }
        if (damageBenefits) {
            note += " Damaged/wrong item reported within %d days: no return fee.".formatted(DAMAGE_REPORT_DAYS);
        }
        if (amount.signum() <= 0) {
            return new Decision(Outcome.INELIGIBLE, BigDecimal.ZERO, "Nothing left to refund on this order.");
        }
        return withinLimit(amount.setScale(2, RoundingMode.HALF_UP), note);
    }

    private Decision withinLimit(BigDecimal amount, String note) {
        if (amount.compareTo(cfg.autoApproveLimit()) > 0) {
            return new Decision(Outcome.NEEDS_APPROVAL, amount,
                    note + " Amount exceeds the $%s auto-approval limit: requires a human specialist."
                            .formatted(cfg.autoApproveLimit()));
        }
        return new Decision(Outcome.AUTO_APPROVE, amount, note + " Within auto-approval limit.");
    }
}
