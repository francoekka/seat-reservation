package com.paytm.money.reservation.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {
    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void incConfirmed() {
        registry.counter("reservations_confirmed_total").increment();
    }

    public void incSeatTaken() {
        registry.counter("reservations_declined_total", "reason", "seat_taken").increment();
    }

    public void incUserLimit() {
        registry.counter("reservations_declined_total", "reason", "user_limit").increment();
    }

    public void incIdempotencyMismatch() {
        registry.counter("reservations_declined_total", "reason", "idempotency_mismatch").increment();
    }

    public void setSeatsAvailable(int count) {
        registry.gauge("seats_available_gauge", count);
    }
}
