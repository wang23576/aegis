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
        // 1:1 trajectory evidence: every runtime event is appended verbatim (compact fields),
        // and each LLM invocation is aggregated into one step (DSH's step model).
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_event (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              turn_no INTEGER NOT NULL,
              seq INTEGER NOT NULL,
              event_type TEXT NOT NULL,
              event_id TEXT,
              reply_id TEXT,
              tool_call_id TEXT,
              block_id TEXT,
              created_at TEXT,
              data TEXT,
              FOREIGN KEY(session_id) REFERENCES chat_session(id)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chat_step (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              turn_no INTEGER NOT NULL,
              step_no INTEGER NOT NULL,
              reply_id TEXT,
              thinking TEXT,
              content TEXT,
              tool_calls TEXT,
              data_blocks TEXT,
              usage TEXT,
              started_at INTEGER,
              first_token_at INTEGER,
              ended_at INTEGER,
              duration_ms INTEGER,
              interrupted INTEGER DEFAULT 0,
              status TEXT,
              FOREIGN KEY(session_id) REFERENCES chat_session(id)
            )""");
        // Skill version history. The active skill itself lives on the filesystem
        // (workspace/skills/<name>/SKILL.md) so Workspace tab can browse it and the
        // framework loads it natively; this table only snapshots past revisions.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS skill_version (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              agent_id TEXT NOT NULL,
              skill_name TEXT NOT NULL,
              version INTEGER NOT NULL,
              description TEXT,
              content TEXT NOT NULL,
              saved_at TEXT NOT NULL DEFAULT (datetime('now')),
              note TEXT,
              UNIQUE(agent_id, skill_name, version)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_skillver_skill ON skill_version(agent_id, skill_name, version)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_session_user ON chat_session(user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_event_session ON chat_event(session_id,seq)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_step_session ON chat_step(session_id,turn_no,step_no)");
    }
}
