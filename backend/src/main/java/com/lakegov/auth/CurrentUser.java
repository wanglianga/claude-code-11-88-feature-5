package com.lakegov.auth;

import java.util.Map;

public record CurrentUser(long id, String username, String displayName, String role) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public Map<String, Object> toMap() {
        return Map.of("id", id, "username", username, "displayName", displayName, "role", role);
    }
}
