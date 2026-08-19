package com.grassland.identity.security;

/** Public HTTP authorization categories used by the identity controller manifest. */
public enum IdentityAccessLevel {
    PUBLIC,
    TOKEN_AUTHENTICATED,
    AUTHENTICATED,
    ORGANIZATION_SCOPED,
    STORE_SCOPED,
    ADMIN,
    BACKEND_ROLE,
    SERVICE
}
