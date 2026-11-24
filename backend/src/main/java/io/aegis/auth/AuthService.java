package io.aegis.auth;

import io.aegis.user.User;
import io.aegis.user.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    public static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final UserRepository users;
    private final JwtUtil jwt;

    public AuthService(UserRepository users, JwtUtil jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    /** Returns JWT on success, empty on bad credentials. */
    public Optional<String> login(String username, String password) {
        Optional<User> found = users.findByUsername(username);
        if (found.isEmpty()) return Optional.empty();
        User u = found.get();
        if (!ENCODER.matches(password, u.passwordHash)) return Optional.empty();
        return Optional.of(jwt.generate(u.id, u.username, u.role));
    }
}
