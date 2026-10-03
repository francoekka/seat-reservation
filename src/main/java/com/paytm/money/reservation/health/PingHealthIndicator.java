package com.paytm.money.reservation.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ping")
public class PingHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        return Health.up().withDetail("ping", "pong").build();
    }
}
