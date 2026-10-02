package com.snapland.gis.dev;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("dev")
@EnableConfigurationProperties(DevProperties.class)
class DevConfig {
}
