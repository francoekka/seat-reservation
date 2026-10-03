package com.paytm.money.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ReservationController {
    private final ReservationService reservationService;
    private final ObjectMapper mapper;

    public ReservationController(ReservationService reservationService, ObjectMapper mapper) {
        this.reservationService = reservationService;
        this.mapper = mapper;
    }

    @PostMapping("/{showId}/reserve")
    public ResponseEntity<String> reserve(@PathVariable("showId") UUID showId,
                                          @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
                                          @RequestBody ReserveRequest req,
                                          Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getPrincipal().toString();

        try {
            // Canonicalize payload: stable JSON string for hashing
            String payloadJson = mapper.writeValueAsString(req);
            return reservationService.reserve(showId, req, idempotencyKey, payloadJson, userId);
        } catch (Exception e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Reservation failed");
        }
    }
}
