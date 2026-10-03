package com.snapland.gis.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

        jdbcTemplate.update("""
                INSERT INTO users (id, name, email, created_at)
                VALUES (?::uuid, ?, ?, NOW())
                ON CONFLICT (id) DO NOTHING
                """, userId, "Dev User", "dev@snapland.local");

        log.info("Dev bootstrap: user={}", userId);
    }
}
