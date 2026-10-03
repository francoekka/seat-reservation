package com.paytm.money.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.domain.entity.ReservationEntity;
import com.paytm.money.reservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class ReservationController {
    private final ReservationService reservationService;
    private final ObjectMapper mapper;

    public ReservationController(ReservationService reservationService, ObjectMapper mapper) {
        this.reservationService = reservationService;
        this.mapper = mapper;
    }

    /**
     * Reserve seats for a show.
     * Header: Idempotency-Key
     * Body: ReserveRequest
     */
    @PostMapping("/shows/{showId}/reserve")
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
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Reservation failed");
        }
    }

    /**
     * Lightweight ownership verification.
     * Returns 200 if caller is owner, 403 if not, 404 if missing.
     */
    @PostMapping("/reservations/{reservationId}/verify-owner")
    public ResponseEntity<Map<String, Object>> verifyOwner(@PathVariable UUID reservationId,
                                                           Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getPrincipal().toString();

        // assertReservationOwnedBy will throw 404 or 403 as appropriate
        ReservationEntity reservation = reservationService.assertReservationOwnedBy(reservationId, userId);

        return ResponseEntity.ok(Map.of(
                "reservation_id", reservation.getId().toString(),
                "user_id", reservation.getUserId(),
                "status", reservation.getStatus()
        ));
    }

    /**
     * Cancel a reservation. Only the reservation owner may cancel.
     * Idempotent: repeated cancels by owner return 200 OK.
     */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelReservation(@PathVariable UUID reservationId,
                                                                 Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getPrincipal().toString();

        Map<String, Object> resp = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(resp);
    }
}
