package com.paytm.money.reservation.service;

import com.paytm.money.reservation.domain.dto.ShowRequest;
import com.paytm.money.reservation.domain.entity.ShowEntity;
import com.paytm.money.reservation.repository.ShowRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ShowService {
    private final ShowRepository showRepository;

    public ShowService(ShowRepository showRepository) {
        this.showRepository = showRepository;
    }

    public UUID createShow(ShowRequest req, String userId) {
        ShowEntity show = new ShowEntity();
        show.setId(UUID.randomUUID());
        show.setName(req.getName());
        show.setTotalSeats(req.getTotalSeats());
        show.setCreatedBy(userId);
        showRepository.save(show);
        return show.getId();
    }

    public List<ShowEntity> listShows() {
        return showRepository.findAll();
    }

    public ShowEntity getShow(UUID showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new RuntimeException("Show not found"));
    }

    public void deleteShow(UUID showId, String userId) {
        ShowEntity show = getShow(showId);
        if (!show.getCreatedBy().equals(userId)) {
            throw new RuntimeException("Forbidden: not owner");
        }
        showRepository.delete(show);
    }
}
