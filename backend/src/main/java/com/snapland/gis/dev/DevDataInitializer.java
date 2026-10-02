package com.snapland.gis.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development-only bootstrap. Seeds one User, one Workspace, and the OWNER membership.
 * Remove this class when authentication and workspace management are implemented.
 */
@Component
@Profile("dev")
class DevDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final DevProperties devProperties;
    private final JdbcTemplate jdbcTemplate;

    DevDataInitializer(DevProperties devProperties, JdbcTemplate jdbcTemplate) {
        this.devProperties = devProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String userId = devProperties.userId().toString();
        String workspaceId = devProperties.workspaceId().toString();

        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, created_at)
                VALUES (?::uuid, ?, ?, NOW())
                ON CONFLICT (id) DO NOTHING
                """, userId, "Dev User", "dev@snapland.local");

        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, created_by, created_at)
                VALUES (?::uuid, ?, ?::uuid, NOW())
                ON CONFLICT (id) DO NOTHING
                """, workspaceId, "Dev Workspace", userId);

        jdbcTemplate.update("""
                INSERT INTO workspace_member (id, workspace_id, user_id, role)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'OWNER')
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """, workspaceId, userId);

        log.info("Dev bootstrap: user={} workspace={}", userId, workspaceId);
    }
}
