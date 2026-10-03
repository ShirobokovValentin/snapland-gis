package com.snapland.gis.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(DrawingRateLimitProperties.class)
public class DrawingRateLimitConfig implements WebMvcConfigurer {

    private final DrawingRateLimitInterceptor interceptor;

    public DrawingRateLimitConfig(DrawingRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/workspaces/**");
    }
}
