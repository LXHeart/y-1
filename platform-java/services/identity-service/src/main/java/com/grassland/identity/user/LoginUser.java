package com.grassland.identity.user;

public record LoginUser(String id, String email, String displayName, String role, String status, String passwordHash) {
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
