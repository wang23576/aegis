package io.aegis.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Simple SQLite-backed datasource. Aegis MVP stores metadata (users, roles,
 * agents, sessions, trajectory) here; agent runtime files live on the filesystem.
 */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource() {
        String dbPath = System.getenv().getOrDefault("AEGIS_DB_PATH", "./data/aegis.db");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
