package io.aegis.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Authenticates JWT on all /api/** routes except /api/auth/login. */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwt;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthFilter(JwtUtil jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.equals("/api/auth/login") || p.startsWith("/actuator") || !p.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(res, "Missing bearer token");
            return;
        }
        Claims claims;
        try {
            claims = jwt.parse(header.substring(7));
        } catch (Exception e) {
            unauthorized(res, "Invalid or expired token");
            return;
        }
        Long id = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        AuthContext.set(new AuthContext.CurrentUser(id, username, role));
        try {
            chain.doFilter(req, res); // do NOT swallow downstream exceptions
        } finally {
            AuthContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("error", msg));
    }
}
