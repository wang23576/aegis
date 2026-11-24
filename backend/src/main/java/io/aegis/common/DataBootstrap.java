package io.aegis.common;

import io.aegis.agent.AgentRepository;
import io.aegis.auth.AuthService;
import io.aegis.user.User;
import io.aegis.user.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seeds the initial SUPER_ADMIN (admin) and the builtin 'claw' agent on first boot.
 * Credentials are overridable via env so nothing is hardcoded in production.
 */
@Component
public class DataBootstrap {

    private final UserRepository users;
    private final AgentRepository agents;
    private final DbInitializer dbInitializer; // dependency ensures schema exists before seeding

    public DataBootstrap(UserRepository users, AgentRepository agents, DbInitializer dbInitializer) {
        this.users = users;
        this.agents = agents;
        this.dbInitializer = dbInitializer;
    }

    @PostConstruct
    public void seed() throws Exception {
        // 1. admin super user
        String adminUser = System.getenv().getOrDefault("AEGIS_ADMIN_USER", "admin");
        String adminPass = System.getenv().getOrDefault("AEGIS_ADMIN_PASSWORD", "admin123");
        User admin = users.findByUsername(adminUser).orElse(null);
        if (admin == null) {
            users.create(adminUser, AuthService.ENCODER.encode(adminPass), "SUPER_ADMIN", "Administrator");
            admin = users.findByUsername(adminUser).orElseThrow();
        }

        // 2. builtin claw agent owned by admin
        if (agents.findById("claw").isEmpty()) {
            agents.create("claw", "claw", "内置通用助手", admin.id, true,
                    "你是 claw，Aegis 平台的内置通用助手。请准确、简洁地回答问题，并遵循 AGENTS.md 中的语言与工具使用规则。",
                    AgentRepository.DEFAULT_TOOLS_JSON, null);
        }

        // 3. workspace scaffold for claw (AGENTS.md carries language + tool-usage rules)
        String wsRoot = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        Path clawWs = Path.of(wsRoot, "claw");
        io.aegis.agent.WorkspaceScaffold.ensure(clawWs, "claw",
                "你是 claw，Aegis 平台的内置通用助手。请准确、简洁地回答问题，并遵循 AGENTS.md 中的语言与工具使用规则。",
                AgentRepository.DEFAULT_TOOLS_JSON);
    }
}
