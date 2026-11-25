package com.lakegov.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号初始化（幂等）。
 * admin/admin123 管理员；reviewer/review123 项目审阅人。
 */
@Component
public class DemoUserSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final String adminPassword;
    private final String reviewerPassword;

    public DemoUserSeeder(JdbcTemplate jdbc,
                          @Value("${lakegov.demo.admin-password}") String adminPassword,
                          @Value("${lakegov.demo.reviewer-password}") String reviewerPassword) {
        this.jdbc = jdbc;
        this.adminPassword = adminPassword;
        this.reviewerPassword = reviewerPassword;
    }

    @Override
    public void run(String... args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        createIfAbsent("admin", encoder.encode(adminPassword), "平台管理员", "ADMIN");
        createIfAbsent("reviewer", encoder.encode(reviewerPassword), "项目审阅人", "REVIEWER");
    }

    private void createIfAbsent(String username, String hash, String displayName, String role) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = ?",
                Integer.class, username);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO app_user(username, password_hash, display_name, role) VALUES (?,?,?,?)",
                    username, hash, displayName, role);
        }
    }
}
