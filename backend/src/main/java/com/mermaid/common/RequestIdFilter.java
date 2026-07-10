package com.mermaid.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with an id, on the response header and in the log context.
 *
 * <p>When a user reports "it said something went wrong", the id in their screenshot is the only way
 * to find the one log line that explains it. The alternative is logging the request body, which for
 * this app would mean logging someone's symptoms.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Honour an upstream id if one was supplied; otherwise mint one.
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The id for the request being handled on this thread, or a placeholder outside one. */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "no-request" : id;
    }
}
