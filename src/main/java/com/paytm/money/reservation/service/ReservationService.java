package com.paytm.money.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.domain.entity.ReservationEntity;
import com.paytm.money.reservation.domain.entity.SeatEntity;
import com.paytm.money.reservation.repository.IdempotencyKeyRepository;
import com.paytm.money.reservation.repository.ReservationRepository;
import com.paytm.money.reservation.repository.SeatRepository;
import com.paytm.money.reservation.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int PER_USER_LIMIT = 4;

    private final SeatRepository seatRepo;
    private final IdempotencyKeyRepository idempRepo;
    private final ReservationRepository reservationRepo;
    private final ObjectMapper mapper;
    private final ReservationMetrics metrics; // may be null if not provided

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

    // --- Metrics helpers ---
    private void confirmReservation() {
        if (metrics != null) metrics.incConfirmed();
    }

    private void handleConflictSeatTaken() {
        if (metrics != null) metrics.incSeatTaken();
    }

    private void handleConflictUserLimit() {
        if (metrics != null) metrics.incUserLimit();
    }

    private void handleConflictIdempotencyMismatch() {
        if (metrics != null) metrics.incIdempotencyMismatch();
    }

    private void updateAvailableSeatsCount(int availableCount) {
        if (metrics != null) metrics.setSeatsAvailable(availableCount);
    }

    // --- Core reservation ---
    @Transactional
    public ResponseEntity<String> reserve(UUID showId, ReserveRequest req,
                                          String idempotencyKey,
                                          String payloadJson,
                                          String userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key");
        }

        String payloadHash = HashUtils.sha256Hex(payloadJson);

        // 1. Try to insert PROCESSING row
        int inserted = idempRepo.tryInsertProcessing(UUID.randomUUID(), idempotencyKey, payloadHash);
        if (inserted > 0) {
            log.debug("Inserted idempotency key {} as PROCESSING", idempotencyKey);
        } else {
            log.debug("Idempotency key {} already exists", idempotencyKey);
        }

        // 2. Lock idempotency row FOR UPDATE
        var idempOpt = idempRepo.findByKeyForUpdate(idempotencyKey);
        if (idempOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency conflict");
        }
        var idemp = idempOpt.get();

        // 3. If COMPLETED, verify payload hash and return cached response
        if ("COMPLETED".equals(idemp.getStatus())) {
            if (!Objects.equals(idemp.getPayloadHash(), payloadHash)) {
                handleConflictIdempotencyMismatch();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
            }
            return ResponseEntity.status(201).body(idemp.getResponseJson());
        }

        // 4. If PROCESSING but payload hash mismatches
        if (!Objects.equals(idemp.getPayloadHash(), payloadHash)) {
            handleConflictIdempotencyMismatch();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
        }

        // 5. Deadlock prevention
        var requested = new ArrayList<>(req.seats());
        if (requested.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No seats requested");
        }
        Collections.sort(requested);

        // 6. Lock seat rows FOR UPDATE
        List<SeatEntity> seats = seatRepo.lockSeatsForUpdate(showId, requested);
        if (seats.size() != requested.size()) {
            handleConflictSeatTaken();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats missing or already taken");
        }

        // 7. Validate availability
        for (SeatEntity s : seats) {
            if (!"AVAILABLE".equals(s.getStatus())) {
                handleConflictSeatTaken();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat not available: " + s.getSeatNumber());
            }
        }

        // 8. Per-user limit check
        int existing = seatRepo.countHeldOrConfirmedByUser(showId, userId);
        if (existing + seats.size() > PER_USER_LIMIT) {
            handleConflictUserLimit();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Per-user limit exceeded");
        }

        // 9. Confirm seats and create reservation
        UUID reservationId = UUID.randomUUID();
        long totalPaise = seats.stream().mapToLong(SeatEntity::getPricePaise).sum();

        for (SeatEntity s : seats) {
            s.setStatus("CONFIRMED");
            s.setReservedByUserId(userId);
            s.setReservationId(reservationId);
        }
        seatRepo.saveAll(seats);

        ReservationEntity reservation = new ReservationEntity();
        reservation.setId(reservationId);
        reservation.setShowId(showId);
        reservation.setUserId(userId);
        reservation.setSeatIds(seats.stream().map(SeatEntity::getId).toArray(UUID[]::new));
        reservation.setTotalPricePaise(totalPaise);
        reservation.setStatus("CONFIRMED");
        reservation.setCreatedAt(Instant.now());
        reservationRepo.save(reservation);

        // 10. Update idempotency row to COMPLETED
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

            confirmReservation();
            return ResponseEntity.status(201).body(responseJson);
        } catch (Exception e) {
            log.error("Failed to serialize reservation response", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Serialization error");
        }
    }

    @Transactional
    public ReservationEntity assertReservationOwnedBy(UUID reservationId, String userId) {
        var opt = reservationRepo.findByIdForUpdate(reservationId);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
        }
        ReservationEntity reservation = opt.get();
        if (!Objects.equals(reservation.getUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not reservation owner");
        }
        return reservation;
    }

    @Transactional
    public Map<String, Object> cancelReservation(UUID reservationId, String userId) {
        var opt = reservationRepo.findByIdForUpdate(reservationId);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
        }
        ReservationEntity reservation = opt.get();

        if (!Objects.equals(reservation.getUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not reservation owner");
        }

        if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
            List<String> existingSeats = reservation.getSeatIds() == null
                    ? Collections.emptyList()
                    : Arrays.stream(reservation.getSeatIds()).map(UUID::toString).collect(Collectors.toList());
            return Map.of(
                    "reservation_id", reservation.getId().toString(),
                    "status", "CANCELLED",
                    "seats", existingSeats
            );
        }

        List<SeatEntity> seats = seatRepo.findByReservationIdForUpdate(reservationId);
        for (SeatEntity s : seats) {
            s.setStatus("AVAILABLE");
            s.setReservedByUserId(null);
            s.setReservationId(null);
        }
        seatRepo.saveAll(seats);

        reservation.setStatus("CANCELLED");
        reservation.setCancelledAt(Instant.now());
        reservationRepo.save(reservation);

        // optional: you can add a metrics helper for cancelled if you want
        log.info("Cancelled reservation {} by user {} released seats {}", reservationId, userId,
                seats.stream().map(SeatEntity::getSeatNumber).collect(Collectors.toList()));

        return Map.of(
                "reservation_id", reservationId.toString(),
                "status", "CANCELLED",
                "seats", seats.stream().map(SeatEntity::getSeatNumber).collect(Collectors.toList())
        );
    }
}
