package com.grassland.trust.dispute;

import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** 客服案终局 + deferred objection 提升 standard successor 的单一事务边界。 */
@Component
public class MerchantRejectionFinalizer {

	private final DisputeCaseRepository disputes;
	private final DeferredDisputeRequestRepository requests;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public MerchantRejectionFinalizer(DisputeCaseRepository disputes, DeferredDisputeRequestRepository requests,
			OutboxRepository outbox, TransactionalOperator transactions) {
		this.disputes = disputes;
		this.requests = requests;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	/** 仅用于已加载并校验 kind/status 的 merchant_rejection；并发输家回读既有终局结果。 */
	public Mono<Finalization> finalizeCase(DisputeCase source, String decision, String decidedBy) {
		Mono<Finalization> work = disputes.forceFinalize(source.id(), decision, decidedBy)
				.flatMap(finalized -> requests.findPendingBySource(source.id())
						.flatMap(request -> promote(finalized, request))
						.switchIfEmpty(outbox.append(finalizedEnvelope(finalized, null))
								.thenReturn(new Finalization(finalized, null, null))))
				.switchIfEmpty(Mono.defer(
						() -> disputes.findById(source.id()).map(existing -> new Finalization(existing, null, null))));
		return transactions.transactional(work);
	}

	private Mono<Finalization> promote(DisputeCase finalized, DeferredDisputeRequest request) {
		String successorId = UUID.randomUUID().toString();
		return disputes.createWithId(successorId, request.engagementRef(), request.organizationId(),
				request.recommenderAccountId(), "recommender", request.reason(), "standard", finalized.premiumSupport())
				.switchIfEmpty(Mono.error(new IllegalStateException("deferred dispute successor could not be created")))
				.flatMap(successor -> requests.markPromoted(request.id(), successor.id())
						.switchIfEmpty(
								Mono.error(new IllegalStateException("deferred request promotion state changed")))
						.flatMap(promoted -> outbox.append(finalizedEnvelope(finalized, successor.id()))
								.then(outbox.append(openedEnvelope(successor)))
								.thenReturn(new Finalization(finalized, successor, promoted))));
	}

	private EventEnvelope finalizedEnvelope(DisputeCase d, String successorDisputeId) {
		Map<String, Object> payload = basePayload(d);
		payload.put("status", d.status());
		payload.put("kind", d.kind());
		payload.put("finalDecision", d.finalDecision());
		if (successorDisputeId != null) {
			payload.put("settlementDeferred", true);
			payload.put("successorDisputeId", successorDisputeId);
		}
		return envelope("DisputeFinalized", d, payload);
	}

	private EventEnvelope openedEnvelope(DisputeCase d) {
		Map<String, Object> payload = basePayload(d);
		payload.put("status", d.status());
		payload.put("kind", d.kind());
		if (d.reason() != null) {
			payload.put("reason", d.reason());
		}
		return envelope("DisputeOpened", d, payload);
	}

	private Map<String, Object> basePayload(DisputeCase d) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("disputeId", d.id());
		payload.put("engagementRef", d.engagementRef());
		payload.put("organizationId", d.organizationId());
		payload.put("openedByAccountId", d.openedByAccountId());
		payload.put("openedByRole", d.openedByRole());
		return payload;
	}

	private EventEnvelope envelope(String eventType, DisputeCase d, Map<String, Object> payload) {
		String eventId = UUID
				.nameUUIDFromBytes((eventType + ":" + d.id() + ":" + d.version()).getBytes(StandardCharsets.UTF_8))
				.toString();
		return new EventEnvelope(eventId, eventType, "DisputeCase", d.id(), d.version(), Instant.now(), null, payload);
	}

	public record Finalization(DisputeCase finalized, DisputeCase successor, DeferredDisputeRequest request) {
	}
}
