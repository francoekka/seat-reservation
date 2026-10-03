package com.paytm.money.reservation.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Repository
public class ReservationUserLockRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private final ConcurrentMap<String, ReentrantLock> testLocks = new ConcurrentHashMap<>();

    public void ensureLockRow(UUID showId, String userId) {
        String databaseProduct = entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try {
                return connection.getMetaData().getDatabaseProductName();
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot identify database product", exception);
            }
        });

        if ("PostgreSQL".equalsIgnoreCase(databaseProduct)) {
            entityManager.createNativeQuery("""
                    INSERT INTO reservation_user_locks (show_id, user_id)
                    VALUES (:showId, :userId)
                    ON CONFLICT (show_id, user_id) DO NOTHING
                    """)
                    .setParameter("showId", showId)
                    .setParameter("userId", userId)
                    .executeUpdate();
            return;
        }

        // H2 is test-only. Retain a local mutex until transaction completion while creating the row.
        String lockName = showId + "\n" + userId;
        ReentrantLock processLock = testLocks.computeIfAbsent(lockName, ignored -> new ReentrantLock());
        processLock.lock();
        entityManager.createNativeQuery("""
                INSERT INTO reservation_user_locks (show_id, user_id)
                SELECT :showId, :userId
                WHERE NOT EXISTS (
                    SELECT 1 FROM reservation_user_locks WHERE show_id = :showId AND user_id = :userId
                )
                """)
                .setParameter("showId", showId)
                .setParameter("userId", userId)
                .executeUpdate();
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
    }

    public void lockUser(UUID showId, String userId) {
        entityManager.createNativeQuery("""
                SELECT user_id FROM reservation_user_locks
                WHERE show_id = :showId AND user_id = :userId
                FOR UPDATE
                """)
                .setParameter("showId", showId)
                .setParameter("userId", userId)
                .getSingleResult();
    }
}
