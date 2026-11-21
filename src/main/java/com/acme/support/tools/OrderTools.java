package com.acme.support.tools;

import com.acme.support.domain.Domain.Order;
import com.acme.support.domain.SupportRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tools exposed to the ReAct order agent.
 * <p>
 * <b>Authorization by construction:</b> an instance is bound to the authenticated customer for one request,
 * so the model never supplies a customer id and cannot reach other customers' orders (a classic
 * "confused deputy" / prompt-injection risk with naive tool design).
 */
public class OrderTools {

    private final SupportRepository repository;
    private final String customerId;
    private final Tracer tracer;

    public OrderTools(SupportRepository repository, String customerId, Tracer tracer) {
        this.repository = repository;
        this.customerId = customerId;
        this.tracer = tracer;
    }

    @Tool("List the customer's orders, most recent first, with status, total, dates and items")
    public String listMyOrders() {
        return traced("listMyOrders", () -> {
            var orders = repository.orders(customerId);
            return orders.isEmpty() ? "The customer has no orders."
                    : orders.stream().map(Order::oneLine).collect(Collectors.joining("\n"));
        });
    }

    @Tool("Get full details of one of the customer's orders")
    public String getOrderDetails(@P("order id, e.g. A-5001") String orderId) {
        return traced("getOrderDetails", () -> repository.order(customerId, orderId)
                .map(o -> o.oneLine() + (o.carrier() != null ? " | carrier %s, tracking %s".formatted(o.carrier(), o.trackingNumber()) : ""))
                .orElse("No order %s found on this customer's account.".formatted(orderId)));
    }

    @Tool("Get the latest shipment tracking events for one of the customer's orders")
    public String trackShipment(@P("order id, e.g. A-5002") String orderId) {
        return traced("trackShipment", () -> repository.order(customerId, orderId)
                .map(OrderTools::simulatedTracking)
                .orElse("No order %s found on this customer's account.".formatted(orderId)));
    }

    /** Stand-in for a carrier API call. */
    private static String simulatedTracking(Order o) {
        var fmt = DateTimeFormatter.ofPattern("MMM d");
        return switch (o.status()) {
            case "PROCESSING" -> "%s: not shipped yet; being packed at the warehouse (placed %s)."
                    .formatted(o.id(), o.placedAt().format(fmt));
            case "SHIPPED" -> "%s via %s (%s): %s label created; %s departed regional hub; %s in transit, "
                    .formatted(o.id(), o.carrier(), o.trackingNumber(),
                            o.placedAt().plusDays(1).format(fmt), o.placedAt().plusDays(2).format(fmt),
                            o.placedAt().plusDays(3).format(fmt))
                    + "estimated delivery " + o.placedAt().plusDays(5).format(fmt) + ".";
            case "DELIVERED" -> "%s via %s (%s): delivered %s, left at front door."
                    .formatted(o.id(), o.carrier(), o.trackingNumber(), o.deliveredAt().format(fmt));
            default -> "%s: %s".formatted(o.id(), o.status());
        };
    }

    private String traced(String tool, Supplier<String> body) {
        Span span = tracer.spanBuilder("execute_tool " + tool)
                .setAttribute("gen_ai.operation.name", "execute_tool")
                .setAttribute("gen_ai.tool.name", tool)
                .setAttribute("customer.id", customerId)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
