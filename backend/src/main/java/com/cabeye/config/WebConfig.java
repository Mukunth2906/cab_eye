package com.cabeye.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the static rider app.
 *
 * <p>The prototype is served by {@code python -m http.server} on port
 * 8000 while this service runs on 8080, so every call from the browser is
 * cross-origin. The allowed origins are listed rather than wildcarded
 * even in development, because a wildcard here is exactly the line that
 * survives into production by accident.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cabeye.cors.allowed-origins:http://localhost:8000,http://127.0.0.1:8000,http://localhost:5500,http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
