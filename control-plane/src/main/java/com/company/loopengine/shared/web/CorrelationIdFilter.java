package com.company.loopengine.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = Optional.ofNullable(request.getHeader(HEADER)).filter(v -> !v.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString());
        request.setAttribute(HEADER, id);
        response.setHeader(HEADER, id);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        }
    }
}
