package com.paytm.money.reservation.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String key;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "show_id", nullable = false)
    private UUID showId;
    @Column(nullable = false)
    private String status;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // --- Getters and Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
