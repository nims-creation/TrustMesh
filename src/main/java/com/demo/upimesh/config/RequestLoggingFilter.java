package com.demo.upimesh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every inbound API request with its HTTP method, URI, status code,
 * and response time in milliseconds.
 *
 * Why a filter instead of an AOP @Around advice?
 *   - Filters run at the Servlet level, before the Spring DispatcherServlet.
 *     This means ALL requests are logged, including ones that never reach a
 *     controller (e.g. 404s, pre-auth failures).
 *   - AOP @Around only intercepts methods that Spring manages; it would miss
 *     requests filtered out by Spring Security or content negotiation.
 *
 * @Order(1) ensures this runs first so the timing captures the full pipeline.
 *
 * In production this would be replaced by a structured log aggregator
 * (e.g. ELK stack / Datadog) with correlation IDs.
 */
@Component
@Order(1)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startMs = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            log.info("{} {} → {} ({}ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
        }
    }

    /** Skip logging for static resources and H2 console to reduce noise. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/h2-console")
                || uri.startsWith("/favicon")
                || uri.endsWith(".css")
                || uri.endsWith(".js");
    }
}
