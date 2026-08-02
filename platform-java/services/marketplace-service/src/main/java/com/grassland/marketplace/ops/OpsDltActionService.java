package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 死信重投 / 弃置（GL-P1-OPS-001 Stage 2）。
 *
 * <p><b>为什么重投也要过双人审批</b>：marketplace 消费的是 trust 事件，重投一条
 * {@code DisputeFinalized} 会重新驱动结算对账 —— 那是资金动作，只是隔了一层。
 * 弃置更是不可逆的信息丢失（承认这条事件永不处理）。两者都按 case 走审批。
 *
 * <p>幂等同样落 {@code ops_case_action}（{@code operationId} 唯一索引）：重复提交回放台账，
 * 不会把同一条死信重投两次 —— 重投两次意味着下游收到重复事件，虽然消费侧有 inbox 幂等兜底，
 * 但不该依赖它来掩盖运营台的重复动作。
 *
 * <p>弃置只改状态，不删 Kafka 消息也不删行：死信是审计对象。retention 清理归 GL-P3-PLATFORM-001。
 */
@Service
public class OpsDltActionService {

    private final OpsCaseRepository cases;
    private final OpsCaseActionRepository actions;
    private final OpsCaseAuditRepository audits;
    private final OpsDltMessageRepository messages;
    private final KafkaTemplate<Object, Object> kafka;

    public OpsDltActionService(OpsCaseRepository cases, OpsCaseActionRepository actions,
                               OpsCaseAuditRepository audits, OpsDltMessageRepository messages,
                               KafkaTemplate<Object, Object> kafka) {
        this.cases = cases;
        this.actions = actions;
        this.audits = audits;
        this.messages = messages;
        this.kafka = kafka;
    }

    /**
     * 对一条死信执行重投或弃置。
     *
     * @param replay true=重投原 topic，false=弃置
     */
    public Mono<OpsCaseAction> execute(String messageId, boolean replay, String operationId,
                                       String actorAccountId, String actorRole) {
        String kind = replay ? OpsCaseAction.DLT_REPLAY : OpsCaseAction.DLT_DISCARD;
        return messages.findById(messageId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "死信消息不存在")))
                .flatMap(message -> cases.findBySource(OpsCaseSource.DLT_MESSAGE, message.position())
                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "该死信缺少处置单")))
                        .flatMap(opsCase -> {
                            if (!"approved".equals(opsCase.status())) {
                                return Mono.error(new MarketplaceException(409, "死信处置须先经双人审批通过"));
                            }
                            // 幂等查询必须排在「已处置」校验之前：第一次成功的重投正是把消息
                            // 推离 pending 的那次，若先校验状态，同一 operationId 的重试会被
                            // 误判成 409 —— 那就不叫幂等了。
                            return actions.findByOperationId(operationId)
                                    .flatMap(existing -> guardReplay(existing, opsCase.id(), kind))
                                    .switchIfEmpty(Mono.defer(() -> {
                                        if (!message.isPending()) {
                                            return Mono.error(new MarketplaceException(409, "该死信已处置"));
                                        }
                                        return actions.claim(opsCase.id(), operationId, kind, actorAccountId)
                                                .flatMap(claimed -> run(message, opsCase, claimed, replay,
                                                        actorAccountId, actorRole))
                                                // 并发同 operationId：对方抢到了，回放它的台账。
                                                .switchIfEmpty(Mono.defer(() -> actions
                                                        .findByOperationId(operationId)
                                                        .flatMap(existing -> guardReplay(existing,
                                                                opsCase.id(), kind))));
                                    }));
                        }));
    }

    private Mono<OpsCaseAction> guardReplay(OpsCaseAction existing, String caseId, String kind) {
        if (!existing.caseId().equals(caseId) || !existing.action().equals(kind)) {
            return Mono.error(new MarketplaceException(409, "operationId 已用于其他处置动作"));
        }
        return Mono.just(existing);
    }

    private Mono<OpsCaseAction> run(OpsDltMessage message, OpsCase opsCase, OpsCaseAction claimed,
                                    boolean replay, String actorAccountId, String actorRole) {
        Mono<String> work = replay ? replay(message) : Mono.just("discarded");
        return work
                // 先标状态再落成功：markReplayed 只吃 pending，抢到就等于拿到了唯一的处置权。
                .flatMap(outcome -> (replay ? messages.markReplayed(message.id())
                        : messages.markDiscarded(message.id()))
                        .switchIfEmpty(Mono.error(new MarketplaceException(409, "该死信已处置")))
                        .thenReturn(outcome))
                .flatMap(outcome -> actions.complete(claimed.operationId(), true, outcome, null)
                        .flatMap(done -> audits.append(opsCase.id(), "action_executed", actorAccountId,
                                        actorRole, opsCase.status(), opsCase.status(),
                                        claimed.action() + ": " + message.originalTopic())
                                .thenReturn(done)))
                .onErrorResume(error -> actions
                        .complete(claimed.operationId(), false, null, describe(error))
                        .flatMap(done -> audits.append(opsCase.id(), "action_failed", actorAccountId,
                                        actorRole, opsCase.status(), opsCase.status(),
                                        claimed.action() + ": " + describe(error))
                                .thenReturn(done)));
    }

    /** 重投原 topic，保留原 key（分区亲和性：同一聚合的事件顺序不能因重投打乱）。 */
    private Mono<String> replay(OpsDltMessage message) {
        return Mono.fromFuture(() -> kafka
                        .send(message.originalTopic(), message.messageKey(), message.payload())
                        .toCompletableFuture())
                .thenReturn("replayed:" + message.originalTopic());
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
