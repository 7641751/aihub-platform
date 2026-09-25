package com.aihub.admin.web.internal;

import com.aihub.common.internal.InternalHmac;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/** 守卫 /internal/**：缺签名或不合法一律 401。该前缀永远不应该暴露到公网。 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final long MAX_SKEW_SECONDS = 300;

    private final String secret;

    public InternalAuthFilter(@Value("${aihub.internal.secret:}") String secret) {
        this.secret = secret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String timestamp = request.getHeader("X-Internal-Timestamp");
        String signature = request.getHeader("X-Internal-Signature");
        if (secret == null || secret.isBlank() || !fresh(timestamp)
                || !InternalHmac.verify(secret, timestamp, request.getMethod(), request.getRequestURI(), signature)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid internal signature\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean fresh(String timestamp) {
        try {
            long skew = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            return skew <= MAX_SKEW_SECONDS;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
