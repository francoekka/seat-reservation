package com.paytm.money.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricsIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readinessIncludesDatabaseHealth() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.db.status").value("UP"))
            .andExpect(jsonPath("$.components.ping.status").value("UP"));
    }

    @Test
    void prometheusEndpointExposesCustomMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(header().exists("x-correlation-id"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reservations_confirmed_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reason=\"seat_taken\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reason=\"per_user_limit\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reason=\"idempotency_mismatch\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reason=\"idempotent_replay\"")));
    }
}