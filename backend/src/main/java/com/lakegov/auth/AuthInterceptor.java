package com.lakegov.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 轻量 Token 认证（演示用）：
 * 登录返回 base64(username) 作为 Bearer Token；拦截器按用户名解析当前用户。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbc;

    public AuthInterceptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(401, "missing bearer token");
            return false;
        }
        String token = header.substring(7).trim();
        String username;
        try {
            username = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            response.sendError(401, "invalid token");
            return false;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, display_name, role FROM app_user WHERE username = ?", username);
        if (rows.isEmpty()) {
            response.sendError(401, "unknown user");
            return false;
        }
        Map<String, Object> row = rows.get(0);
        CurrentUser user = new CurrentUser(
                ((Number) row.get("id")).longValue(),
                (String) row.get("username"),
                (String) row.get("display_name"),
                (String) row.get("role"));
        // 项目审阅人（REVIEWER）只读：写操作仅管理员（ADMIN）
        if (!user.isAdmin() && !"GET".equalsIgnoreCase(request.getMethod())) {
            response.sendError(403, "REVIEWER 角色仅允许只读访问");
            return false;
        }
        request.setAttribute("currentUser", user);
        return true;
    }

    public static CurrentUser require(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute("currentUser");
    }
}
