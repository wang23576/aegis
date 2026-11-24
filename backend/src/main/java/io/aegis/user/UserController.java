package io.aegis.user;

import io.aegis.auth.AuthContext;
import io.aegis.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** User management - SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final AuthService auth;

    public UserController(UserRepository users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    private void requireSuperAdmin() {
        AuthContext.CurrentUser cur = AuthContext.get();
        if (cur == null || !cur.isSuperAdmin()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "super admin only");
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        requireSuperAdmin();
        return ResponseEntity.ok(users.findAll().stream()
                .map(u -> Map.of("id", u.id, "username", u.username,
                        "role", u.role, "displayName", u.displayName == null ? "" : u.displayName,
                        "createdAt", u.createdAt == null ? "" : u.createdAt))
                .toList());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        requireSuperAdmin();
        String username = body.get("username");
        String password = body.get("password");
        String role = body.getOrDefault("role", "USER");
        String displayName = body.getOrDefault("displayName", username);
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password required"));
        }
        if (!List.of("SUPER_ADMIN", "AGENT_ADMIN", "USER").contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
        }
        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username exists"));
        }
        long id = users.create(username, AuthService.ENCODER.encode(password), role, displayName);
        return ResponseEntity.ok(Map.of("id", id, "username", username, "role", role));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable long id, @RequestBody Map<String, String> body) {
        requireSuperAdmin();
        String role = body.get("role");
        if (role == null || !List.of("SUPER_ADMIN", "AGENT_ADMIN", "USER").contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
        }
        int n = users.updateRole(id, role);
        return n > 0 ? ResponseEntity.ok(Map.of("ok", true))
                     : ResponseEntity.notFound().build();
    }
}
