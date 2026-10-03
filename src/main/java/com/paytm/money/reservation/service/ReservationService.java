package com.paytm.money.reservation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.money.reservation.domain.dto.ReserveRequest;
import com.paytm.money.reservation.domain.entity.ReservationEntity;
import com.paytm.money.reservation.domain.entity.SeatEntity;
import com.paytm.money.reservation.repository.IdempotencyKeyRepository;
import com.paytm.money.reservation.repository.ReservationRepository;
import com.paytm.money.reservation.repository.ReservationUserLockRepository;
import com.paytm.money.reservation.repository.SeatRepository;
import com.paytm.money.reservation.repository.ShowRepository;
import com.paytm.money.reservation.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final ReservationUserLockRepository userLockRepo;
    private final ShowRepository showRepo;
    private final ObjectMapper mapper;
    private final ReservationMetrics metrics; // may be null if not provided

    public ReservationService(SeatRepository seatRepo,
                              IdempotencyKeyRepository idempRepo,
                              ReservationRepository reservationRepo,
                              ReservationUserLockRepository userLockRepo,
                              ShowRepository showRepo,
                              ObjectMapper mapper,
                              ReservationMetrics metrics) {
        this.seatRepo = seatRepo;
        this.idempRepo = idempRepo;
        this.reservationRepo = reservationRepo;
        this.userLockRepo = userLockRepo;
        this.showRepo = showRepo;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // --- Metrics helpers ---
    private void handleConflictSeatTaken() {
        if (metrics != null) metrics.incSeatTaken();
    }

    private void handleConflictUserLimit() {
        if (metrics != null) metrics.incUserLimit();
    }

    private void handleConflictIdempotencyMismatch() {
        if (metrics != null) metrics.incIdempotencyMismatch();
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void refreshAvailableGauge(UUID showId) {
        if (metrics != null) {
            metrics.setSeatsAvailable(showId, seatRepo.countByShowIdAndStatus(showId, "AVAILABLE"));
        }
    }

    // --- Core reservation ---
    @Transactional
    public ResponseEntity<Map<String, Object>> reserve(UUID showId, ReserveRequest req,
                                          String idempotencyKey,
                                          String userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key");
        }
        if (!showRepo.existsById(showId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Show not found");
        }

        if (req == null || req.seats() == null || req.seats().isEmpty()
                || req.seats().stream().anyMatch(seat -> seat == null || seat.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one valid seat is required");
        }
        List<String> requested = new ArrayList<>(req.seats());
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate seats are not allowed");
        }
        Collections.sort(requested);

        String scopedKey = HashUtils.sha256Hex(showId + "\n" + userId + "\n" + idempotencyKey);
        String canonicalPayload = showId + "\n" + userId + "\n" + String.join("\n", requested);
        String payloadHash = HashUtils.sha256Hex(canonicalPayload);

        // A unique insert plus row lock serializes parallel retries of this scoped key.
        idempRepo.tryInsertProcessing(UUID.randomUUID(), scopedKey, payloadHash, userId, showId);
        var idempOpt = idempRepo.findByKeyForUpdate(scopedKey);
        if (idempOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency conflict");
        }
        var idemp = idempOpt.get();

        // Replays are scoped by authenticated user and show; the hash binds the sorted seat list.
        if ("COMPLETED".equals(idemp.getStatus())) {
            if (!Objects.equals(idemp.getPayloadHash(), payloadHash)) {
                handleConflictIdempotencyMismatch();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
            }
            if (metrics != null) metrics.incIdempotentReplay();
            try {
                return ResponseEntity.status(201).body(mapper.readValue(idemp.getResponseJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {}));
            } catch (Exception e) {
                throw new IllegalStateException("Stored idempotent response is invalid", e);
            }
        }
        if (!Objects.equals(idemp.getPayloadHash(), payloadHash)) {
            handleConflictIdempotencyMismatch();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency payload mismatch");
        }

        // Serialize this caller's quota check across distinct seat requests.
        userLockRepo.ensureLockRow(showId, userId);
        userLockRepo.lockUser(showId, userId);

        // All-or-nothing: seat rows are acquired in sorted order to avoid multi-seat deadlocks.
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

        Map<String, Object> response = Map.of(
                "reservation_id", reservationId.toString(),
                "show_id", showId.toString(),
                "user_id", userId,
                "seats", requested,
                "amount_paise", totalPaise,
                "status", "confirmed"
        );
        try {
            String responseJson = mapper.writeValueAsString(response);
            idemp.setStatus("COMPLETED");
            idemp.setResponseJson(responseJson);
            idempRepo.save(idemp);

            afterCommit(() -> {
                if (metrics != null) metrics.incConfirmed();
                refreshAvailableGauge(showId);
            });
            return ResponseEntity.status(201).body(response);
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
            List<String> existingSeats = reservation.getSeatIds() == null ? Collections.emptyList()
                : seatRepo.findAllById(Arrays.asList(reservation.getSeatIds())).stream()
                .map(SeatEntity::getSeatNumber).sorted().collect(Collectors.toList());
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

        afterCommit(() -> refreshAvailableGauge(reservation.getShowId()));

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
