package com.paytm.money.reservation.controller;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.domain.dto.CreateShowResponse;
import com.paytm.money.reservation.service.ShowAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/shows")
public class AdminController {
    private final ShowAdminService showAdminService;

    public AdminController(ShowAdminService showAdminService) {
        this.showAdminService = showAdminService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateShowResponse> createShow(@RequestBody CreateShowRequest req,
                                                         Authentication authentication) {
        var resp = showAdminService.createShow(req, authentication.getName());
        return ResponseEntity.status(201).body(resp);
    }
}
