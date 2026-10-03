package com.paytm.money.reservation.service;

import com.paytm.money.reservation.domain.dto.CreateShowRequest;
import com.paytm.money.reservation.domain.dto.CreateShowResponse;
import com.paytm.money.reservation.domain.entity.ShowEntity;
import com.paytm.money.reservation.domain.entity.SeatEntity;
import com.paytm.money.reservation.repository.ShowRepository;
import com.paytm.money.reservation.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public CreateShowResponse createShow(CreateShowRequest req) {
        UUID showId = UUID.randomUUID();
        ShowEntity show = new ShowEntity(showId, req.name(), req.seats().size());
        showRepo.save(show);

        List<SeatEntity> seats = req.seats().stream()
                .map(s -> new SeatEntity(UUID.randomUUID(), showId, s.seatNumber(), s.pricePaise(), "AVAILABLE"))
                .collect(Collectors.toList());

        seatRepo.saveAll(seats);

        List<String> seatNumbers = seats.stream().map(SeatEntity::getSeatNumber).collect(Collectors.toList());
        return new CreateShowResponse(showId, req.name(), seats.size(), seatNumbers);
    }
}
