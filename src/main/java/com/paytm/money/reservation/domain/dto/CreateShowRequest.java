package com.paytm.money.reservation.domain.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateShowRequest(String name, List<String> seats,
                                @JsonProperty("price_paise") long pricePaise) {
}
