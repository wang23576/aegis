package io.aegis.auth;

import io.aegis.user.User;
import io.aegis.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password required"));
        }
        Optional<String> token = auth.login(username, password);
        if (token.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "bad credentials"));
        }
        User u = users.findByUsername(username).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "token", token.get(),
                "username", u.username,
                "role", u.role,
                "displayName", u.displayName == null ? u.username : u.displayName,
                "userId", u.id));
    }

    /** Current user info (used by frontend to restore session). */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        AuthContext.CurrentUser cur = AuthContext.get();
        return ResponseEntity.ok(Map.of(
                "id", cur.id, "username", cur.username, "role", cur.role));
    }
}
