package io.aegis.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/** Minimal JWT helper for Aegis. Secret is configurable via env, default only for local dev. */
@Component
public class JwtUtil {

    private final SecretKey key;
    private static final long EXPIRE_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    public JwtUtil() {
        String secret = System.getenv().getOrDefault(
                "AEGIS_JWT_SECRET", "aegis-local-dev-secret-key-please-override-in-production-32b");
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    public String generate(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_MS))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
