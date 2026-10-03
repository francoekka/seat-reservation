package com.paytm.money.reservation.domain.dto;

import java.util.UUID;
import java.util.List;

public record CreateShowResponse(UUID showId, String name, int totalSeats, List<String> seatNumbers) {}
