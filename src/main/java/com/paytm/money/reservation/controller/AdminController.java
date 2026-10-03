package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.domain.dto.CreateShowResponse;
import com.paytm.money.reservation.service.ShowAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class AdminController {
    private final ShowAdminService showAdminService;

    public AdminController(ShowAdminService showAdminService) {
        this.showAdminService = showAdminService;
    }

    /**
     * Admin endpoint to create a show with explicit seat list.
     * Example payload:
     * {
     *   "name": "Bollywood Night",
     *   "seats": [
     *     {"seatNumber":"A1","pricePaise":25000},
     *     {"seatNumber":"A2","pricePaise":25000}
     *   ]
     * }
     */
    @PostMapping
    public ResponseEntity<CreateShowResponse> createShow(@RequestBody CreateShowRequest req) {
        var resp = showAdminService.createShow(req);
        return ResponseEntity.status(201).body(resp);
    }
}
