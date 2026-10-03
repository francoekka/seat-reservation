package com.paytm.money.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MetricsIntegrationTests {

    @Autowired MockMvc mockMvc;

    @Test
    void prometheusEndpointExposesCustomMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reservations_confirmed_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reservations_declined_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("seats_available_gauge")));
    }
}
