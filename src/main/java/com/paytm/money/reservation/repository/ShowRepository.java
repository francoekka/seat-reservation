package com.paytm.money.reservation.repository;

import com.paytm.money.reservation.domain.entity.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> { }
