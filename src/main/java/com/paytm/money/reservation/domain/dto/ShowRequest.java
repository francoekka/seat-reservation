package com.paytm.money.reservation.domain.dto;

public class ShowRequest {
    private String name;
    private int totalSeats;

    // --- Getters ---
    public String getName() {
        return name;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    // --- Setters ---
    public void setName(String name) {
        this.name = name;
    }

    public void setTotalSeats(int totalSeats) {
        this.totalSeats = totalSeats;
    }
}
