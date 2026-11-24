package io.aegis.user;

/** User domain object. role is single-valued per Aegis design. */
public class User {
    public Long id;
    public String username;
    public String passwordHash;
    public String role;
    public String displayName;
    public String createdAt;

    public User() {}

    public User(Long id, String username, String passwordHash, String role, String displayName) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = displayName;
    }
}
