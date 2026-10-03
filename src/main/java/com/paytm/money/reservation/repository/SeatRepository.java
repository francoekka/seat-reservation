package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.SeatEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<SeatEntity, UUID> {
    // Used later by reservation flow; included here for completeness
    @Query(value = "SELECT * FROM seats WHERE show_id = :showId AND seat_number IN (:seatNumbers) ORDER BY seat_number ASC FOR UPDATE", nativeQuery = true)
    List<SeatEntity> lockSeatsForUpdate(@Param("showId") UUID showId, @Param("seatNumbers") List<String> seatNumbers);

    @Query("SELECT COUNT(s) FROM SeatEntity s WHERE s.showId = :showId AND s.reservedByUserId = :userId AND s.status IN ('HELD','CONFIRMED')")
    int countHeldOrConfirmedByUser(@Param("showId") UUID showId, @Param("userId") String userId);
}
