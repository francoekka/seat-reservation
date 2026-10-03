package com.paytm.money.reservation.config;

import io.micrometer.core.instrument.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public ReservationMetrics reservationMetrics(MeterRegistry registry) {
        return new ReservationMetrics(registry);
    }

    public static class ReservationMetrics {
        private final Counter confirmed;
        private final Counter declinedSeatTaken;
        private final Counter declinedUserLimit;
        private final Counter declinedIdempotencyMismatch;
        private volatile double seatsAvailableValue;

        public ReservationMetrics(MeterRegistry registry) {
            this.confirmed = Counter.builder("reservations_confirmed_total")
                    .description("Total confirmed reservations")
                    .register(registry);

            this.declinedSeatTaken = Counter.builder("reservations_declined_total")
                    .description("Declined reservations")
                    .tag("reason","seat_taken")
                    .register(registry);

            this.declinedUserLimit = Counter.builder("reservations_declined_total")
                    .description("Declined reservations")
                    .tag("reason","user_limit_exceeded")
                    .register(registry);

            this.declinedIdempotencyMismatch = Counter.builder("reservations_declined_total")
                    .description("Declined reservations")
                    .tag("reason","idempotency_mismatch")
                    .register(registry);

            Gauge.builder("seats_available_gauge", this, r -> r.seatsAvailableValue)
                    .description("Number of seats currently available")
                    .register(registry);
        }

        public void incConfirmed() { confirmed.increment(); }
        public void incSeatTaken() { declinedSeatTaken.increment(); }
        public void incUserLimit() { declinedUserLimit.increment(); }
        public void incIdempotencyMismatch() { declinedIdempotencyMismatch.increment(); }
        public void setSeatsAvailable(double v) { this.seatsAvailableValue = v; }
    }
}
