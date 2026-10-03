package com.paytm.money.reservation.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class ReservationEntity {

    @Id
    private UUID id;

    private UUID showId;
    private String userId;
    private String status;

    private Instant createdAt;
    private Instant cancelledAt;

    @Column(name = "seat_ids")
    private UUID[] seatIds;

    private long totalPricePaise;

    // --- Getters and Setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public UUID[] getSeatIds() { return seatIds; }
    public void setSeatIds(UUID[] seatIds) { this.seatIds = seatIds; }

    public long getTotalPricePaise() { return totalPricePaise; }
    public void setTotalPricePaise(long totalPricePaise) { this.totalPricePaise = totalPricePaise; }
}
