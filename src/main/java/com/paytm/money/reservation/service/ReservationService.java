package com.paytm.money.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.domain.entity.*;
import com.paytm.money.reservation.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int PER_USER_LIMIT = 4;

    private final SeatRepository seatRepo;
    private final IdempotencyKeyRepository idempRepo;
    private final ReservationRepository reservationRepo;
    private final ObjectMapper mapper;
    private final ReservationMetrics metrics;
    private final ReservationRepository reservationRepo;

    public ReservationService(SeatRepository seatRepo,
                              IdempotencyKeyRepository idempRepo,
                              ReservationRepository reservationRepo,
                              ObjectMapper mapper,
                              ReservationMetrics metrics) {
        this.seatRepo = seatRepo;
        this.idempRepo = idempRepo;
        this.reservationRepo = reservationRepo;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public ReservationService(ReservationRepository reservationRepo) {
        this.reservationRepo = reservationRepo;
    }

    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reserve seats atomically. Returns 201 with reservation JSON or throws ResponseStatusException(409).
     * Idempotency-Key header must be provided by controller and payloadJson is the canonical request body string.
     */
    @Transactional
    public ResponseEntity<String> reserve(UUID showId, ReserveRequest req, String idempotencyKey, String payloadJson, String userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key");
        }

        String payloadHash = sha256(payloadJson);

        // 1. Try to insert PROCESSING row (non-blocking). If returns 0, row already exists.
        int inserted = idempRepo.tryInsertProcessing(idempotencyKey, payloadHash);

        // 2. Lock idempotency row FOR UPDATE to serialize in-flight identical keys
        var idempOpt = idempRepo.findByKeyForUpdate(idempotencyKey);
        if (idempOpt.isEmpty()) {
            // This should not happen because tryInsertProcessing either inserted or row existed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency conflict");
        }
        var idemp = idempOpt.get();

        // 3. If COMPLETED, verify payload hash and return cached response
        if ("COMPLETED".equals(idemp.getStatus())) {
            if (!idemp.getPayloadHash().equals(payloadHash)) {
                metrics.incrementDeclined("idempotency_mismatch");
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
            }
            return ResponseEntity.status(201).body(idemp.getResponseJson());
        }

        // 4. If PROCESSING but payload hash mismatches, reject
        if (!idemp.getPayloadHash().equals(payloadHash)) {
            metrics.incrementDeclined("idempotency_mismatch");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
        }

        // 5. Deadlock prevention: deterministic ordering
        var requested = new ArrayList<>(req.seats());
        if (requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No seats requested");
        }
        Collections.sort(requested);

        // 6. Lock seat rows FOR UPDATE in deterministic order
        List<SeatEntity> seats = seatRepo.lockSeatsForUpdate(showId, requested);
        if (seats.size() != requested.size()) {
            metrics.incrementDeclined("seat_taken");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats missing or already taken");
        }

        // 7. Validate availability
        for (SeatEntity s : seats) {
            if (!"AVAILABLE".equals(s.getStatus())) {
                metrics.incrementDeclined("seat_taken");
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat not available: " + s.getSeatNumber());
            }
        }

        // 8. Per-user limit check (locked count)
        int existing = seatRepo.countHeldOrConfirmedByUser(showId, userId);
        if (existing + seats.size() > PER_USER_LIMIT) {
            metrics.incrementDeclined("user_limit_exceeded");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Per-user limit exceeded");
        }

        // 9. Confirm seats and create reservation
        UUID reservationId = UUID.randomUUID();
        long totalPaise = seats.stream().mapToLong(SeatEntity::getPricePaise).sum();

        for (SeatEntity s : seats) {
            s.setStatus("CONFIRMED");
            s.setReservedByUserId(userId);
            s.setReservationId(reservationId);
            seatRepo.save(s);
        }

        ReservationEntity reservation = new ReservationEntity();
        reservation.setId(reservationId);
        reservation.setShowId(showId);
        reservation.setUserId(userId);
        reservation.setSeatIds(seats.stream().map(SeatEntity::getId).toArray(UUID[]::new));
        reservation.setTotalPricePaise(totalPaise);
        reservation.setStatus("CONFIRMED");
        reservationRepo.save(reservation);

        // 10. Update idempotency row to COMPLETED with response JSON
        try {
            var response = Map.of(
                    "reservation_id", reservationId.toString(),
                    "seats", requested,
                    "total_price_paise", totalPaise
            );
            String responseJson = mapper.writeValueAsString(response);
            idemp.setStatus("COMPLETED");
            idemp.setResponseJson(responseJson);
            idempRepo.save(idemp);

            metrics.incrementConfirmed();
            return ResponseEntity.status(201).body(responseJson);
        } catch (Exception e) {
            log.error("Failed to serialize reservation response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Serialization error");
        }
    }

    /**
     * Lock reservation row FOR UPDATE and assert that the provided userId is the owner.
     * - Throws 404 if reservation not found.
     * - Throws 403 if the token-derived userId is not the reservation owner.
     *
     * This method is transactional so the FOR UPDATE lock is held while the caller continues
     * work in the same transaction (useful for cancellation where seats are released afterwards).
     */
    @Transactional
    public ReservationEntity assertReservationOwnedBy(UUID reservationId, String userId) {
        var opt = reservationRepo.findByIdForUpdate(reservationId);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
        }
        ReservationEntity reservation = opt.get();
        if (!reservation.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not reservation owner");
        }
        return reservation;
    }
}
