package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.ReservationEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    /**
     * Lock the reservation row for update to serialize concurrent operations.
     * Uses native query to ensure SELECT ... FOR UPDATE semantics.
     */
    @Query(value = "SELECT * FROM reservations WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ReservationEntity> findByIdForUpdate(@Param("id") UUID id);
}
