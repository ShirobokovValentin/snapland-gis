package com.snapland.gis;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Singleton container: started once via static initializer and kept alive for the
// entire test run (Ryuk stops it on JVM exit). This avoids the Testcontainers
// JUnit 5 per-class lifecycle management that would stop and restart the container
// between test classes, invalidating Spring Boot's cached application context.
abstract class IntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("snapland_gis")
                .withUsername("snapland")
                .withPassword("snapland");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
