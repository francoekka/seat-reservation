package com.paytm.money.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MetricsIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointExposesCustomMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(httpBasic("demo", "demo")))
                .andExpect(status().isOk())
                .andExpect(header().exists("x-correlation-id"));
    }
}