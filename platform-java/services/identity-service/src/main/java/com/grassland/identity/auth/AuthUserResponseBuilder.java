package com.grassland.identity.auth;

import com.grassland.identity.admin.BackendRoleRepository;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.user.AuthUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Builds the public user contract shared by login and {@code /auth/me}. */
@Component
public class AuthUserResponseBuilder {

	private final BackendRoleRepository backendRoles;

	public AuthUserResponseBuilder(BackendRoleRepository backendRoles) {
		this.backendRoles = backendRoles;
	}

	public Mono<Map<String, Object>> build(AuthUser user, boolean mustChangePassword, String username) {
		return backendRoles.findByAccountId(user.id()).defaultIfEmpty(java.util.Set.of())
				.map(roles -> toMap(user, mustChangePassword, username, roles));
	}

	private static Map<String, Object> toMap(AuthUser user, boolean mustChangePassword, String username,
			java.util.Set<BackendRole> roles) {
		Map<String, Object> userInfo = new LinkedHashMap<>();
		userInfo.put("id", user.id());
		userInfo.put("email", user.email());
		if (username != null && !username.isBlank()) {
			userInfo.put("username", username);
		}
		userInfo.put("hasEmail", user.email() != null && !user.email().endsWith("@sub.grassland.invalid"));
		if (user.displayName() != null && !user.displayName().isBlank()) {
			userInfo.put("displayName", user.displayName());
		}
		userInfo.put("role", user.role());
		userInfo.put("roles", roles.stream().map(BackendRole::dbValue).sorted().toList());
		userInfo.put("mustChangePassword", mustChangePassword);
		return userInfo;
	}
}
