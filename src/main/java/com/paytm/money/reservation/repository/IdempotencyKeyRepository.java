package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.IdempotencyKeyEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Repository
public class IdempotencyKeyRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private final ConcurrentMap<String, ReentrantLock> testLocks = new ConcurrentHashMap<>();

    @Transactional
    public int tryInsertProcessing(UUID id, String key, String payloadHash, String userId, UUID showId) {
        String databaseProduct = entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try {
                return connection.getMetaData().getDatabaseProductName();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot identify database product", exception);
            }
        });

        if ("PostgreSQL".equalsIgnoreCase(databaseProduct)) {
            return entityManager.createNativeQuery("""
                    INSERT INTO idempotency_keys
                        (id, idempotency_key, payload_hash, status, user_id, show_id, created_at)
                    VALUES (:id, :key, :payloadHash, 'PROCESSING', :userId, :showId, now())
                    ON CONFLICT (idempotency_key) DO NOTHING
                    """)
                    .setParameter("id", id)
                    .setParameter("key", key)
                    .setParameter("payloadHash", payloadHash)
                    .setParameter("userId", userId)
                    .setParameter("showId", showId)
                    .executeUpdate();
        }

        // H2 is test-only; serialize initial insertion in-process. Production uses PostgreSQL ON CONFLICT.
        ReentrantLock processLock = testLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        processLock.lock();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    processLock.unlock();
                }
            });
        } else {
            processLock.unlock();
        }
        List<IdempotencyKeyEntity> existing = entityManager.createQuery(
                        "SELECT k FROM IdempotencyKeyEntity k WHERE k.key = :key", IdempotencyKeyEntity.class)
                .setParameter("key", key)
                .setMaxResults(1)
                .getResultList();
        if (!existing.isEmpty()) {
            return 0;
        }

        IdempotencyKeyEntity entity = new IdempotencyKeyEntity();
        entity.setId(id);
        entity.setKey(key);
        entity.setPayloadHash(payloadHash);
        entity.setUserId(userId);
        entity.setShowId(showId);
        entity.setStatus("PROCESSING");
        entity.setCreatedAt(java.time.Instant.now());
        entityManager.persist(entity);
        entityManager.flush();
        return 1;
    }

    @Transactional
    public Optional<IdempotencyKeyEntity> findByKeyForUpdate(String key) {
        List<IdempotencyKeyEntity> rows = entityManager.createQuery(
                        "SELECT k FROM IdempotencyKeyEntity k WHERE k.key = :key", IdempotencyKeyEntity.class)
                .setParameter("key", key)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst();
    }

    @Transactional
    public void save(IdempotencyKeyEntity entity) {
        entityManager.merge(entity);
    }
}
