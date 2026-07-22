package com.grassland.identity.user;

public record AuthUser(String id, String email, String displayName, String role, String status) {
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
