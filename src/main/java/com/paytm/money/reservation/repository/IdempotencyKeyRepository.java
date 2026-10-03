package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO idempotency_keys(key, payload_hash, status, created_at, updated_at) VALUES (:key, :hash, 'PROCESSING', now(), now()) ON CONFLICT (key) DO NOTHING", nativeQuery = true)
    int tryInsertProcessing(@Param("key") String key, @Param("hash") String hash);

    @Query(value = "SELECT * FROM idempotency_keys WHERE key = :key FOR UPDATE", nativeQuery = true)
    Optional<IdempotencyKeyEntity> findByKeyForUpdate(@Param("key") String key);

    Optional<IdempotencyKeyEntity> findByKey(String key);
}
