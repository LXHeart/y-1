package com.grassland.identity.event;

import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxRepositoryIT extends IdentityItSupport {

	@Autowired
	private OutboxRepository repository;

	@BeforeEach
	void clearOutbox() {
		db.sql("DELETE FROM outbox").then().block();
	}

	@Test
	void claimBatch_claimsOnlyDueRows_andReclaimsOnlyExpiredLeases() {
		repository.append(event("due")).block();
		repository.append(event("later")).block();
		db.sql("UPDATE outbox SET next_attempt_at = now() + interval '60 seconds' WHERE event_id = 'later'").then()
				.block();
		UUID firstToken = UUID.randomUUID();
		UUID secondToken = UUID.randomUUID();

		var first = repository.claimBatch(10, firstToken, Duration.ofSeconds(30)).collectList().block();

		assertThat(first).extracting(OutboxRepository.OutboxRow::eventId).containsExactly("due");
		assertThat(first.getFirst().claimToken()).isEqualTo(firstToken);
		assertThat(first.getFirst().attemptCount()).isEqualTo(1);
		assertThat(repository.claimBatch(10, secondToken, Duration.ofSeconds(30)).collectList().block()).isEmpty();

		db.sql("UPDATE outbox SET claimed_until = now() - interval '1 second' WHERE event_id = 'due'").then().block();
		var reclaimed = repository.claimBatch(10, secondToken, Duration.ofSeconds(30)).collectList().block();
		assertThat(reclaimed).extracting(OutboxRepository.OutboxRow::eventId).containsExactly("due");
		assertThat(reclaimed.getFirst().claimToken()).isEqualTo(secondToken);
		assertThat(reclaimed.getFirst().attemptCount()).isEqualTo(2);
	}

	@Test
	void conditionalTransitions_rejectStaleOwners_andTrackPendingAge() {
		repository.append(event("old")).block();
		db.sql("""
				UPDATE outbox
				SET created_at = now() - interval '120 seconds',
				    next_attempt_at = now() - interval '1 second'
				WHERE event_id = 'old'
				""").then().block();
		UUID token = UUID.randomUUID();
		OutboxRepository.OutboxRow claim = repository.claimBatch(1, token, Duration.ofSeconds(30)).single().block();

		assertThat(repository.markPublished(claim.id(), UUID.randomUUID()).block()).isFalse();
		assertThat(repository.markFailure(claim.id(), claim.claimToken(), Duration.ofSeconds(10), "TimeoutException")
				.block()).isTrue();
		assertThat(repository.pendingCount().block()).isEqualTo(1L);
		assertThat(repository.oldestPendingAgeSeconds().block()).isBetween(119L, 122L);
		assertThat(repository.claimBatch(1, UUID.randomUUID(), Duration.ofSeconds(30)).collectList().block()).isEmpty();

		db.sql("UPDATE outbox SET next_attempt_at = now() - interval '1 second' WHERE event_id = 'old'").then().block();
		OutboxRepository.OutboxRow retry = repository.claimBatch(1, UUID.randomUUID(), Duration.ofSeconds(30)).single()
				.block();
		assertThat(retry.attemptCount()).isEqualTo(2);
		assertThat(repository.markPublished(retry.id(), retry.claimToken()).block()).isTrue();
		assertThat(repository.pendingCount().block()).isZero();
		assertThat(repository.oldestPendingAgeSeconds().block()).isZero();
	}

	@Test
	void markPublished_isConditional_andRemovesRowFromPendingBacklog() {
		repository.append(event("publish")).block();
		OutboxRepository.OutboxRow claim = repository.claimBatch(1, UUID.randomUUID(), Duration.ofSeconds(30)).single()
				.block();

		assertThat(repository.markPublished(claim.id(), claim.claimToken()).block()).isTrue();
		assertThat(repository.markPublished(claim.id(), claim.claimToken()).block()).isFalse();
		assertThat(repository.pendingCount().block()).isZero();
	}

	@Test
	void v10Migration_upgradesRuntimeCreatedLegacyOutbox() throws Exception {
		String schema = "identity_v10_" + UUID.randomUUID().toString().replace("-", "");
		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()); var statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + schema);
			statement.execute("CREATE TABLE " + schema + ".outbox ("
					+ "id uuid PRIMARY KEY, event_id text NOT NULL UNIQUE, event_type text NOT NULL, "
					+ "aggregate_type text NOT NULL, aggregate_id text NOT NULL, payload json NOT NULL, "
					+ "created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz)");
		}

		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.defaultSchema(schema).schemas(schema).table("identity_v10_history").locations("classpath:db/migration")
				.baselineOnMigrate(true).baselineVersion("9").load().migrate();

		try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
				var statement = connection.prepareStatement(
						"SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'outbox'")) {
			statement.setString(1, schema);
			try (var result = statement.executeQuery()) {
				Set<String> columns = new java.util.HashSet<>();
				while (result.next()) {
					columns.add(result.getString(1));
				}
				assertThat(columns).contains("attempt_count", "next_attempt_at", "claimed_until", "claim_token",
						"last_error_code");
				assertThat(columns).doesNotContain("aggregate_version", "occurred_at", "correlation_id", "claimed_at",
						"last_error", "failed_at");
			}
		}
	}

	private static EventEnvelope event(String eventId) {
		return new EventEnvelope(eventId, "IdentityChanged", "Identity", UUID.randomUUID().toString(), 3, Instant.now(),
				null, java.util.Map.of("eventId", eventId));
	}
}
