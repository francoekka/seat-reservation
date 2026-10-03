package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.domain.entity.ReservationEntity;
import com.paytm.money.reservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Reserve seats for a show.
     * Header: Idempotency-Key
     * Body: ReserveRequest
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<Map<String, Object>> reserve(@PathVariable("showId") UUID showId,
                                          @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
                                          @RequestBody ReserveRequest req,
                                          Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        return reservationService.reserve(showId, req, idempotencyKey, authentication.getName());
    }

    /**
     * Lightweight ownership verification.
     * Returns 200 if caller is owner, 403 if not, 404 if missing.
     */
    @PostMapping("/reservations/{reservationId}/verify-owner")
    public ResponseEntity<Map<String, Object>> verifyOwner(@PathVariable("reservationId") UUID reservationId,
                                                           Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getName();

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
    public ResponseEntity<Map<String, Object>> cancelReservation(@PathVariable("reservationId") UUID reservationId,
                                                                 Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getName();

        Map<String, Object> resp = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(resp);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
