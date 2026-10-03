package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.domain.dto.CreateShowResponse;
import com.paytm.money.reservation.service.ShowAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/shows")
public class AdminController {
    private final ShowAdminService showAdminService;

    public AdminController(ShowAdminService showAdminService) {
        this.showAdminService = showAdminService;
    }

    @PostMapping
    public ResponseEntity<CreateShowResponse> createShow(@RequestBody CreateShowRequest req) {
        var resp = showAdminService.createShow(req);
        return ResponseEntity.status(201).body(resp);
    }
}
