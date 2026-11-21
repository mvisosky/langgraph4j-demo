package com.acme.support.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Domain records shared by repositories, tools and policy services. */
public final class Domain {

    private Domain() {}

    public enum Tier { STANDARD, GOLD, PLATINUM }

    public record Customer(String id, String name, String email, Tier tier) {
        public String firstName() {
            return name.split(" ")[0];
        }

        /** Profile line handed to prompts - deliberately excludes the email address (data minimisation). */
        public String promptProfile() {
            return "%s (id %s), %s member".formatted(name, id, tier);
        }
    }

    public record OrderItem(String sku, String name, int quantity, BigDecimal unitPrice, boolean finalSale) {
        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public record Order(String id, String customerId, String status, BigDecimal total,
                        OffsetDateTime placedAt, OffsetDateTime deliveredAt,
                        String carrier, String trackingNumber, List<OrderItem> items) {

        public String oneLine() {
            var itemNames = items.stream()
                    .map(i -> "%s%s [sku %s, $%s]".formatted(i.name(), i.finalSale() ? " (final sale)" : "", i.sku(), i.lineTotal()))
                    .toList();
            return "%s | %s | $%s | placed %s%s | %s".formatted(
                    id, status, total, placedAt.toLocalDate(),
                    deliveredAt != null ? " | delivered " + deliveredAt.toLocalDate() : "",
                    String.join("; ", itemNames));
        }
    }
}
