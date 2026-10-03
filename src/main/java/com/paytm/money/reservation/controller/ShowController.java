package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.ShowRequest;
import com.paytm.money.reservation.domain.entity.ShowEntity;
import com.paytm.money.reservation.service.ShowService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /**
     * Create a new show.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createShow(@RequestBody ShowRequest req,
                                                          Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getPrincipal().toString();

        UUID showId = showService.createShow(req, userId);

        return ResponseEntity.ok(Map.of(
                "id", showId.toString(),
                "name", req.getName(),
                "totalSeats", req.getTotalSeats()
        ));
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
    public ResponseEntity<ShowEntity> getShow(@PathVariable UUID showId) {
        return ResponseEntity.ok(showService.getShow(showId));
    }

    /**
     * Delete a show.
     */
    @DeleteMapping("/{showId}")
    public ResponseEntity<Void> deleteShow(@PathVariable UUID showId,
                                           Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthenticated");
        }
        String userId = authentication.getPrincipal().toString();

        showService.deleteShow(showId, userId);
        return ResponseEntity.noContent().build();
    }
}
