package com.snapland.gis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SnaplandGisApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
