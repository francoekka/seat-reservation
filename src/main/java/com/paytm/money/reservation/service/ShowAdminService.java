package com.paytm.money.reservation.service;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.domain.dto.CreateShowResponse;
import com.paytm.money.reservation.domain.entity.ShowEntity;
import com.paytm.money.reservation.domain.entity.SeatEntity;
import com.paytm.money.reservation.repository.ShowRepository;
import com.paytm.money.reservation.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShowAdminService {
    private final ShowRepository showRepo;
    private final SeatRepository seatRepo;

    public ShowAdminService(ShowRepository showRepo, SeatRepository seatRepo) {
        this.showRepo = showRepo;
        this.seatRepo = seatRepo;
    }

    /**
     * Creates a show and bulk-inserts seats in AVAILABLE state inside a single transaction.
     * Throws DataIntegrityViolationException if duplicate seat_number for the same show is attempted.
     */
    @Transactional
    public CreateShowResponse createShow(CreateShowRequest req, String userId) {
        if (req.name() == null || req.name().isBlank() || req.seats() == null || req.seats().isEmpty()
                || req.pricePaise() < 0 || req.seats().stream().anyMatch(s -> s == null || s.isBlank())
                || new HashSet<>(req.seats()).size() != req.seats().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid show or seat details");
        }
        UUID showId = UUID.randomUUID();
        ShowEntity show = new ShowEntity(showId, req.name(), req.seats().size());
        show.setCreatedBy(userId);
        showRepo.save(show);

        List<SeatEntity> seats = req.seats().stream()
                .map(s -> new SeatEntity(UUID.randomUUID(), showId, s, req.pricePaise(), "AVAILABLE"))
                .collect(Collectors.toList());

        seatRepo.saveAll(seats);

        List<String> seatNumbers = seats.stream().map(SeatEntity::getSeatNumber).collect(Collectors.toList());
        return new CreateShowResponse(showId, req.name(), seats.size(), seatNumbers);
    }
}
