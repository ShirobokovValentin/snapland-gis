package com.snapland.gis;

import com.snapland.gis.config.PrototypeProperties;
import com.snapland.gis.dev.DevProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("test")
class TestDataInitializer implements ApplicationRunner {

    private final DevProperties devProperties;
    private final PrototypeProperties prototypeProperties;
    private final JdbcTemplate jdbcTemplate;

    TestDataInitializer(DevProperties devProperties, PrototypeProperties prototypeProperties,
                        JdbcTemplate jdbcTemplate) {
        this.devProperties = devProperties;
        this.prototypeProperties = prototypeProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String userId = devProperties.userId().toString();
        String workspaceId = prototypeProperties.workspaceId().toString();

        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, created_at)
                VALUES (?::uuid, ?, ?, NOW())
                ON CONFLICT (id) DO NOTHING
                """, userId, "Test User", "test@snapland.local");

        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, created_by, created_at)
                VALUES (?::uuid, ?, ?::uuid, NOW())
                ON CONFLICT (id) DO NOTHING
                """, workspaceId, "Test Workspace", userId);

        jdbcTemplate.update("""
                INSERT INTO workspace_member (id, workspace_id, user_id, role)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 'OWNER')
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """, workspaceId, userId);
    }
}
