package com.eda.choreography.infra.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets an editor served from elsewhere (a static site opened with {@code ?api=<engine>}) call the
 * API. Off unless {@code choreography.web.cors-origins} lists the origins.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfig(@Value("${choreography.web.cors-origins:}") String[] origins) {
        this.origins = origins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length > 0 && !origins[0].isBlank()) {
            registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "PUT", "POST");
        }
    }
}
