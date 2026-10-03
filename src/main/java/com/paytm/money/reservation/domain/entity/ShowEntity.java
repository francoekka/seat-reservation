package com.paytm.money.reservation.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class ShowEntity {
  @Id
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Column(name = "total_seats", nullable = false)
  private int totalSeats;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "created_by")
  private String createdBy;

  public ShowEntity() {}

  public ShowEntity(UUID id, String name, int totalSeats) {
    this.id = id;
    this.name = name;
    this.totalSeats = totalSeats;
    this.createdAt = Instant.now();
  }

  // --- Getters / Setters ---
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public int getTotalSeats() { return totalSeats; }
  public void setTotalSeats(int totalSeats) { this.totalSeats = totalSeats; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
