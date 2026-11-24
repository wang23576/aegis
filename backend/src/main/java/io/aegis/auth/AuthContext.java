package io.aegis.auth;

/** Per-request identity, stored in reactor context / thread local via WebFilter. */
public class AuthContext {
    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser u) { HOLDER.set(u); }
    public static CurrentUser get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    public static class CurrentUser {
        public final Long id;
        public final String username;
        public final String role;

        public CurrentUser(Long id, String username, String role) {
            this.id = id;
            this.username = username;
            this.role = role;
        }

        public boolean isSuperAdmin() { return "SUPER_ADMIN".equals(role); }
        public boolean isAgentAdmin() { return "AGENT_ADMIN".equals(role); }
        public boolean isUser() { return "USER".equals(role); }
    }
}
