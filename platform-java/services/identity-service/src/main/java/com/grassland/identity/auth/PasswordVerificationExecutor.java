package com.grassland.identity.auth;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import com.grassland.identity.security.PasswordVerifier;

/**
 * Bounded executor for bcrypt/Argon2 verification, which is deliberately
 * synchronous.
 */
@Component
public class PasswordVerificationExecutor {

	private final Scheduler scheduler;

	public PasswordVerificationExecutor(@Value("${identity.password-verification.max-threads:4}") int maxThreads,
			@Value("${identity.password-verification.max-queue:128}") int maxQueue) {
		this.scheduler = Schedulers.newBoundedElastic(Math.max(1, maxThreads), Math.max(1, maxQueue),
				"identity-password");
	}

	public Mono<Boolean> verify(PasswordVerifier verifier, String password, String hash) {
		return Mono.fromCallable(() -> verifier.verify(password, hash)).subscribeOn(scheduler);
	}

	@PreDestroy
	void dispose() {
		scheduler.dispose();
	}
}
