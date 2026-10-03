package com.paytm.money.reservation.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final String HEADER = "x-correlation-id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String cid = req.getHeader(HEADER);
            if (cid == null || cid.isBlank()) {
                cid = UUID.randomUUID().toString();
            }
            MDC.put(HEADER, cid);
            res.setHeader(HEADER, cid);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(HEADER);
        }
    }
}
