package com.grassland.identity.auth;

public class IdentityException extends RuntimeException {
    private final int status;

    public IdentityException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
