package com.paytm.money.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationCorrectnessTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void hotSeatRaceHasOneWinnerAndStateReconciles() throws Exception {
        String showId = createShow("hot-" + UUID.randomUUID(), List.of("A12"));
        List<CompletableFuture<Integer>> requests = IntStream.range(0, 48)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> reserveStatus(showId, "A12",
                        "buyer-" + index, "hot-key-" + index)))
                .toList();
        long winners = requests.stream().mapToInt(CompletableFuture::join).filter(status -> status == 201).count();
        long declines = requests.stream().mapToInt(CompletableFuture::join).filter(status -> status == 409).count();
        long failures = requests.stream().mapToInt(CompletableFuture::join).filter(status -> status >= 500).count();
        assertEquals(1, winners);
        assertEquals(47, declines);
        assertEquals(0, failures);

        JsonNode state = objectMapper.readTree(mockMvc.perform(get("/shows/{id}", showId))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, state.path("total_seats").asInt());
        assertEquals(1, state.path("confirmed").asInt());
        assertEquals(0, state.path("available").asInt());
        assertEquals(state.path("total_seats").asInt(), state.path("available").asInt()
                + state.path("held").asInt() + state.path("confirmed").asInt());
    }

    @Test
    void parallelRequestsCannotExceedPerUserLimit() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 10).mapToObj(index -> "S" + index).toList();
        String showId = createShow("limit-" + UUID.randomUUID(), seats);
        List<CompletableFuture<Integer>> requests = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> reserveStatus(showId, "S" + index,
                        "same-user", "limit-key-" + index)))
                .toList();
        long winners = requests.stream().mapToInt(CompletableFuture::join).filter(status -> status == 201).count();
        assertEquals(4, winners);
        assertEquals(6, requests.stream().mapToInt(CompletableFuture::join).filter(status -> status == 409).count());
        assertEquals(0, requests.stream().mapToInt(CompletableFuture::join).filter(status -> status >= 500).count());

        JsonNode state = objectMapper.readTree(mockMvc.perform(get("/shows/{id}", showId))
                .andReturn().getResponse().getContentAsString());
        assertEquals(10, state.path("total_seats").asInt());
        assertEquals(state.path("total_seats").asInt(), state.path("available").asInt()
                + state.path("held").asInt() + state.path("confirmed").asInt());
    }

    @Test
    void retriesAreScopedAndCancellationRequiresOwner() throws Exception {
        String showId = createShow("idem-" + UUID.randomUUID(), List.of("R1", "R2"));
        String idemKey = "idem-" + UUID.randomUUID();
        var first = mockMvc.perform(post("/shows/{id}/reserve", showId)
                        .with(jwt().jwt(jwt -> jwt.subject("alice")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"R1\"],\"user_id\":\"spoofed\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.amount_paise").value(25000))
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        String reservationId = firstBody.path("reservation_id").asText();

        var replay = mockMvc.perform(post("/shows/{id}/reserve", showId)
                        .with(jwt().jwt(jwt -> jwt.subject("alice")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"R1\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        assertEquals(reservationId, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("reservation_id").asText());

        mockMvc.perform(post("/shows/{id}/reserve", showId)
                        .with(jwt().jwt(jwt -> jwt.subject("alice")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"R2\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        mockMvc.perform(post("/shows/{id}/reserve", showId)
                        .with(jwt().jwt(jwt -> jwt.subject("bob")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"R1\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());

        String secondShow = createShow("idem-scope-" + UUID.randomUUID(), List.of("R1"));
        var otherShow = mockMvc.perform(post("/shows/{id}/reserve", secondShow)
                        .with(jwt().jwt(jwt -> jwt.subject("alice")))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"R1\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        assertNotEquals(reservationId, objectMapper.readTree(otherShow.getResponse().getContentAsString())
                .path("reservation_id").asText());

        mockMvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .with(jwt().jwt(jwt -> jwt.subject("bob"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mockMvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .with(jwt().jwt(jwt -> jwt.subject("alice"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void showCreationRequiresAdminAndCreatesAvailableSeats() throws Exception {
        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"anonymous\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());

        mockMvc.perform(post("/shows")
                        .with(jwt().jwt(jwt -> jwt.subject("not-admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());

        var response = mockMvc.perform(post("/shows")
                        .with(jwt().jwt(jwt -> jwt.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"created\",\"seats\":[\"A1\",\"A2\"],\"price_paise\":100}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(response.getResponse().getContentAsString());
        assertEquals(2, created.path("total_seats").asInt());
        assertEquals("available", created.path("seats").get(0).path("status").asText());
    }

    private String createShow(String name, List<String> seats) throws Exception {
        String seatJson = objectMapper.writeValueAsString(seats);
        String body = "{\"name\":" + objectMapper.writeValueAsString(name)
                + ",\"seats\":" + seatJson + ",\"price_paise\":25000}";
        var result = mockMvc.perform(post("/shows")
                        .with(jwt().jwt(jwt -> jwt.subject("test-admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("show_id").asText();
    }

    private int reserveStatus(String showId, String seat, String user, String key) {
        try {
            return mockMvc.perform(post("/shows/{id}/reserve", showId)
                            .with(jwt().jwt(jwt -> jwt.subject(user)))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seats\":[\"" + seat + "\"]}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
