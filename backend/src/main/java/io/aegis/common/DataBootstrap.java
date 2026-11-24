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
            agents.create("claw", "claw", "Built-in general assistant", admin.id, true,
                    "You are a helpful assistant. Answer accurately and concisely.",
                    AgentRepository.DEFAULT_TOOLS_JSON, null);
        }

        // 3. workspace scaffold for claw
        String wsRoot = System.getenv().getOrDefault("AEGIS_WORKSPACE_ROOT", "./data/workspaces");
        Path clawWs = Path.of(wsRoot, "claw");
        Files.createDirectories(clawWs.resolve("skills/example-skill"));
        Files.createDirectories(clawWs.resolve("subagents"));
        Files.createDirectories(clawWs.resolve("memory"));
        Files.writeString(clawWs.resolve("AGENTS.md"),
                "# claw\n\nYou are a helpful assistant. Answer accurately and concisely.\n");
        Files.writeString(clawWs.resolve("tools.json"), AgentRepository.DEFAULT_TOOLS_JSON);
        Files.writeString(clawWs.resolve("skills/example-skill/SKILL.md"),
                "# Example skill\n\nA starter placeholder skill.\n");
        Files.writeString(clawWs.resolve("subagents/README.md"), "# Subagents\n\nSub-agent definitions go here.\n");
    }
}
