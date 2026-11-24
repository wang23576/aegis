package io.aegis.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final RowMapper<User> MAP = (rs, n) -> {
        User u = new User();
        u.id = rs.getLong("id");
        u.username = rs.getString("username");
        u.passwordHash = rs.getString("password_hash");
        u.role = rs.getString("role");
        u.displayName = rs.getString("display_name");
        u.createdAt = rs.getString("created_at");
        return u;
    };

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        List<User> r = jdbc.query("SELECT * FROM app_user WHERE username=?", MAP, username);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Optional<User> findById(long id) {
        List<User> r = jdbc.query("SELECT * FROM app_user WHERE id=?", MAP, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<User> findAll() {
        return jdbc.query("SELECT * FROM app_user ORDER BY id", MAP);
    }

    public long create(String username, String passwordHash, String role, String displayName) {
        // RETURNING avoids last_insert_rowid() returning 0 with a new connection per op.
        Long id = jdbc.queryForObject(
                "INSERT INTO app_user(username,password_hash,role,display_name) VALUES(?,?,?,?) RETURNING id",
                Long.class, username, passwordHash, role, displayName);
        return id == null ? 0 : id;
    }

    public int updateRole(long id, String role) {
        return jdbc.update("UPDATE app_user SET role=? WHERE id=?", role, id);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return n == null ? 0 : n;
    }
}
