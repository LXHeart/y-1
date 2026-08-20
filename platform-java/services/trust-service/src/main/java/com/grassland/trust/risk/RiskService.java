package com.grassland.trust.risk;

import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.trust.risk.RiskModels.CaseActionRequest;
import com.grassland.trust.risk.RiskModels.Evaluation;
import com.grassland.trust.risk.RiskModels.RegisterSignalRequest;
import com.grassland.trust.risk.RiskModels.Registration;
import com.grassland.trust.risk.RiskModels.RiskCase;
import com.grassland.trust.security.TrustCallerResolver.Caller;
import com.grassland.trust.security.TrustException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Service
public class RiskService {
	private final RiskRepository repository;
	private final RiskRuleEngine rules;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public RiskService(RiskRepository repository, RiskRuleEngine rules, OutboxRepository outbox,
			TransactionalOperator transactions) {
		this.repository = repository;
		this.rules = rules;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	public Mono<Registration> register(RegisterSignalRequest request, Caller actor) {
		Evaluation evaluation = rules.evaluate(request);
		return transactions.transactional(repository.createSignal(request, evaluation).flatMap(created -> {
			if (!created.created())
				return Mono.just(new Registration(created.signal(), null, false));
			if (!evaluation.opensCase())
				return Mono.just(new Registration(created.signal(), null, true));
			return repository.createOrFindActiveCase(created.signal(), evaluation).flatMap(riskCase -> repository
					.attachSignal(riskCase.id(), created.signal().id())
					.then(repository.appendAudit(riskCase.id(), "signal_attached", actor.accountId(),
							actor.isService() ? "service:" + actor.principal() : actor.role(), evaluation.reason()))
					.then(outbox.append(event("RiskCaseSignalAttached", riskCase, created.signal().id())))
					.thenReturn(new Registration(created.signal(), riskCase, true)));
		}));
	}

	public Mono<RiskCase> act(String caseId, CaseActionRequest request, Caller actor) {
		if (request == null || request.action() == null || request.action().isBlank()) {
			return Mono.error(new IllegalArgumentException("action 不能为空"));
		}
		return transactions.transactional(repository
				.transition(caseId, request.action(), actor.accountId(), request.note())
				.switchIfEmpty(Mono.error(new TrustException(409, "案件状态不允许该动作")))
				.flatMap(updated -> repository
						.appendAudit(caseId, request.action(), actor.accountId(), actor.role(), request.note())
						.then(outbox.append(event("RiskCaseUpdated", updated, request.action()))).thenReturn(updated)));
	}

	private static EventEnvelope event(String type, RiskCase riskCase, String detail) {
		Instant now = Instant.now();
		return new EventEnvelope(type + ":" + riskCase.id() + ":" + detail, type, "risk_case", riskCase.id(),
				now.toEpochMilli(), now, UUID.randomUUID().toString(),
				Map.of("caseId", riskCase.id(), "subjectKind", riskCase.subjectKind(), "subjectRef",
						riskCase.subjectRef(), "status", riskCase.status(), "detail", detail));
	}
}
