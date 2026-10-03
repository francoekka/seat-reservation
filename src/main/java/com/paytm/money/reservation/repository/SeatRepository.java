package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<SeatEntity, UUID> {

    /**
     * Lock specific seats for a show in deterministic order to avoid deadlocks.
     * Caller must ensure the list is non-empty and this method is invoked inside a @Transactional method.
     */
    @Query(value = "SELECT * FROM seats WHERE show_id = :showId AND seat_number IN (:seatNumbers) ORDER BY seat_number ASC FOR UPDATE", nativeQuery = true)
    List<SeatEntity> lockSeatsForUpdate(@Param("showId") UUID showId, @Param("seatNumbers") List<String> seatNumbers);

    /**
     * Count seats already HELD or CONFIRMED by a user for a show.
     * Uses JPQL and entity property names.
     */
    @Query("SELECT COUNT(s) FROM SeatEntity s WHERE s.showId = :showId AND s.reservedByUserId = :userId AND s.status IN ('HELD','CONFIRMED')")
    int countHeldOrConfirmedByUser(@Param("showId") UUID showId, @Param("userId") String userId);

    /**
     * Lock all seats that belong to a reservation so they can be released safely.
     * Caller must invoke inside a @Transactional method.
     */
    @Query(value = "SELECT * FROM seats WHERE reservation_id = :reservationId FOR UPDATE", nativeQuery = true)
    List<SeatEntity> findByReservationIdForUpdate(@Param("reservationId") UUID reservationId);
}
