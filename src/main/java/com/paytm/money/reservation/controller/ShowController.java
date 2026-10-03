package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.repository.SeatRepository;
import com.paytm.money.reservation.service.ReservationMetrics;
import com.paytm.money.reservation.service.ShowAdminService;
import com.paytm.money.reservation.domain.entity.ShowEntity;
import com.paytm.money.reservation.service.ShowService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;
    private final ShowAdminService showAdminService;
    private final SeatRepository seatRepository;
    private final ReservationMetrics reservationMetrics;

    public ShowController(ShowService showService, ShowAdminService showAdminService,
                          SeatRepository seatRepository, ReservationMetrics reservationMetrics) {
        this.showService = showService;
        this.showAdminService = showAdminService;
        this.seatRepository = seatRepository;
        this.reservationMetrics = reservationMetrics;
    }

    /**
     * Create a new show.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createShow(@RequestBody CreateShowRequest req,
                                                          Authentication authentication) {
        var created = showAdminService.createShow(req, authentication.getName());
        var seats = seatRepository.findByShowIdOrderBySeatNumberAsc(created.showId());
        reservationMetrics.setSeatsAvailable(created.showId(), seats.size());
        return ResponseEntity.status(201).body(showResponse(created.showId(), created.name(), seats));
    }

    /**
     * List all shows.
     */
    @GetMapping
    public ResponseEntity<List<ShowEntity>> listShows() {
        return ResponseEntity.ok(showService.listShows());
    }

    /**
     * Get details of a single show.
     */
    @GetMapping("/{showId}")
    public ResponseEntity<Map<String, Object>> getShow(@PathVariable("showId") UUID showId) {
        ShowEntity show = showService.getShow(showId);
        var seats = seatRepository.findByShowIdOrderBySeatNumberAsc(showId);
        long available = seats.stream().filter(seat -> "AVAILABLE".equals(seat.getStatus())).count();
        reservationMetrics.setSeatsAvailable(showId, available);
        return ResponseEntity.ok(showResponse(show.getId(), show.getName(), seats));
    }

    private Map<String, Object> showResponse(UUID showId, String name,
                                             List<com.paytm.money.reservation.domain.entity.SeatEntity> seats) {
        long available = seats.stream().filter(seat -> "AVAILABLE".equals(seat.getStatus())).count();
        long held = seats.stream().filter(seat -> "HELD".equals(seat.getStatus())).count();
        long confirmed = seats.stream().filter(seat -> "CONFIRMED".equals(seat.getStatus())).count();
        List<Map<String, Object>> seatStates = new ArrayList<>();
        seats.forEach(seat -> {
            Map<String, Object> state = new HashMap<>();
            state.put("seat_number", seat.getSeatNumber());
            state.put("status", seat.getStatus().toLowerCase());
            state.put("price_paise", seat.getPricePaise());
            seatStates.add(state);
        });
        return Map.of(
                "show_id", showId.toString(),
                "name", name,
                "total_seats", seats.size(),
                "available", available,
                "held", held,
                "confirmed", confirmed,
                "seats", seatStates
        );
    }

    /**
     * Delete a show.
     */
}
