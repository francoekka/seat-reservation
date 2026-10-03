package com.paytm.money.reservation.domain.dto;

import java.util.List;

public record CreateShowRequest(String name, List<SeatCreate> seats) {
    public static record SeatCreate(String seatNumber, int pricePaise) {}
}
