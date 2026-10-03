package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKeyEntity k WHERE k.key = :key")
    Optional<IdempotencyKeyEntity> findByKeyForUpdate(@Param("key") String key);

    @Modifying
    @Query(value = """
        INSERT INTO idempotency_keys (id, key, payload_hash, status, created_at)
        VALUES (:id, :key, :payloadHash, 'PROCESSING', now())
        ON CONFLICT (key) DO NOTHING
        """, nativeQuery = true)
    int tryInsertProcessing(@Param("id") UUID id,
                            @Param("key") String key,
                            @Param("payloadHash") String payloadHash);
}
