package com.cabeye.backend.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} (the admin's daily summary email). */
@Configuration
@EnableScheduling
public class AdminConfig {
}
