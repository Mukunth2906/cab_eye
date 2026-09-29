package com.cabeye.backend.config;

import com.cabeye.backend.admin.AdminInterceptor;
import com.cabeye.backend.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts {@link AuthInterceptor} in front of every REST route. WebSocket upgrades are not MVC. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;
    private final AdminInterceptor admin;

    public WebConfig(AuthInterceptor auth, AdminInterceptor admin) {
        this.auth = auth;
        this.admin = admin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth);
        registry.addInterceptor(admin);
    }
}
