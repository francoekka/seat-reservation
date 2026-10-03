package com.paytm.money.reservation.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReservationMetrics {
    private final MeterRegistry registry;
    private final ConcurrentMap<UUID, AtomicLong> availableByShow = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.counter("reservations_confirmed_total");
        for (String reason : new String[]{"seat_taken", "per_user_limit", "idempotency_mismatch", "idempotent_replay"}) {
            registry.counter("reservations_declined_total", "reason", reason);
        }
        registry.counter("reservations_idempotent_replays_total");
    }

    public void incConfirmed() {
        registry.counter("reservations_confirmed_total").increment();
    }

    public void incSeatTaken() {
        registry.counter("reservations_declined_total", "reason", "seat_taken").increment();
    }

    public void incUserLimit() {
        registry.counter("reservations_declined_total", "reason", "per_user_limit").increment();
    }

    public void incIdempotencyMismatch() {
        registry.counter("reservations_declined_total", "reason", "idempotency_mismatch").increment();
    }

    public void incIdempotentReplay() {
        registry.counter("reservations_idempotent_replays_total").increment();
        registry.counter("reservations_declined_total", "reason", "idempotent_replay").increment();
    }

    public void setSeatsAvailable(UUID showId, long count) {
        AtomicLong available = availableByShow.computeIfAbsent(showId, id -> {
            AtomicLong gaugeValue = new AtomicLong();
            registry.gauge("seats_available_gauge", Tags.of("show_id", id.toString()),
                    gaugeValue, AtomicLong::get);
            return gaugeValue;
        });
        available.set(count);
    }
}
