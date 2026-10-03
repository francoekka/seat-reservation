package com.paytm.money.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTests {

    @Autowired MockMvc mockMvc;

    @Test
    void correlationIdHeaderIsPropagated() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .header("x-correlation-id", "test-cid-123"))
                .andExpect(header().string("x-correlation-id", "test-cid-123"));
    }

    @Test
    void correlationIdIsGeneratedIfMissing() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(header().exists("x-correlation-id"));
    }
}
