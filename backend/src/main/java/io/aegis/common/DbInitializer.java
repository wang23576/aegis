package io.aegis.common;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates schema on startup. Idempotent. */
@Component
public class DbInitializer {

    private final JdbcTemplate jdbc;

    public DbInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS app_user (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              username TEXT NOT NULL UNIQUE,
              password_hash TEXT NOT NULL,
              role TEXT NOT NULL,
              display_name TEXT,
              created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS agent (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              description TEXT,
              owner_id INTEGER NOT NULL,
              builtin INTEGER NOT NULL DEFAULT 0,
              sys_prompt TEXT,
              tools_json TEXT,
              model TEXT,
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              FOREIGN KEY(owner_id) REFERENCES app_user(id)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_session (
              id TEXT PRIMARY KEY,
              agent_id TEXT NOT NULL,
              user_id INTEGER NOT NULL,
              title TEXT,
              permission_mode TEXT NOT NULL DEFAULT 'ACCEPT_EDITS',
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now')),
              FOREIGN KEY(agent_id) REFERENCES agent(id),
              FOREIGN KEY(user_id) REFERENCES app_user(id)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_message (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              role TEXT NOT NULL,
              content TEXT NOT NULL,
              tool_calls TEXT,
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              FOREIGN KEY(session_id) REFERENCES chat_session(id)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_session_user ON chat_session(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id)");
    }
}
