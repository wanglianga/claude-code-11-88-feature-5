package com.lakegov.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbc;

    public AuthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password_hash, display_name, role FROM app_user WHERE username = ?",
                username);
        if (rows.isEmpty() || !new BCryptPasswordEncoder().matches(password,
                (String) rows.get(0).get("password_hash"))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        Map<String, Object> row = rows.get(0);
        String token = Base64.getUrlEncoder()
                .encodeToString(username.getBytes(StandardCharsets.UTF_8));
        return Map.of("token", token,
                "user", Map.of("id", row.get("id"), "username", row.get("username"),
                        "displayName", row.get("display_name"), "role", row.get("role")));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return com.lakegov.auth.AuthInterceptor.require(request).toMap();
    }
}
