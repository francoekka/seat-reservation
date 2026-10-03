package com.paytm.money.reservation.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"show_id","seat_number"}))
public class SeatEntity {
    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    @Column(nullable = false)
    private String status; // AVAILABLE, HELD, CONFIRMED

    @Column(name = "reserved_by_user_id")
    private String reservedByUserId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public SeatEntity() {}

    public SeatEntity(UUID id, UUID showId, String seatNumber, int pricePaise, String status) {
        this.id = id;
        this.showId = showId;
        this.seatNumber = seatNumber;
        this.pricePaise = pricePaise;
        this.status = status;
        this.createdAt = Instant.now();
    }

    // getters / setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }
    public String getSeatNumber() { return seatNumber; }
    public void setSeatNumber(String seatNumber) { this.seatNumber = seatNumber; }
    public int getPricePaise() { return pricePaise; }
    public void setPricePaise(int pricePaise) { this.pricePaise = pricePaise; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReservedByUserId() { return reservedByUserId; }
    public void setReservedByUserId(String reservedByUserId) { this.reservedByUserId = reservedByUserId; }
    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public Instant getCreatedAt() { return createdAt; }
}
