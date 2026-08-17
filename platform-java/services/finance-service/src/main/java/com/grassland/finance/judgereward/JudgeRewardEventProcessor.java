package com.grassland.finance.judgereward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.finance.credits.CreditsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 审判官激励事件处理器（任务书 #31 / ADR-D15 D4）：消费 trust {@code JudgeVoteRewarded}，
 * 调本服务内 {@link CreditsService#awardJudgeReward} 入账。
 *
 * <p>幂等双保险：① {@code finance_inbox}（{@code consumer_name + event_id}）吸收 at-least-once 重投；
 * ② credits 流水 {@code operation_id = judge-reward:{disputeId}:{round}:{judgeAccountId}} 唯一索引。
 * inbox 记账与入账同一 R2DBC 事务（镜像 identity 通知消费者的 7A 约束）。
 * 金额策略集中在 trust（事件载荷带 credits，本处理器只执行）。
 */
@Component
public class JudgeRewardEventProcessor {

    static final String EVENT_TYPE = "JudgeVoteRewarded";

    private static final Logger log = LoggerFactory.getLogger(JudgeRewardEventProcessor.class);

    private final FinanceInboxRepository inbox;
    private final CreditsService credits;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final String consumerName;

    public JudgeRewardEventProcessor(
            FinanceInboxRepository inbox,
            CreditsService credits,
            TransactionalOperator transactions,
            ObjectMapper mapper,
            @Value("${finance.judge-reward-consumer.group-id:finance-judge-reward-consumer}") String consumerName) {
        this.inbox = inbox;
        this.credits = credits;
        this.transactions = transactions;
        this.mapper = mapper;
        this.consumerName = consumerName;
    }

    /** @return 处理结局（PROCESSED / DUPLICATE / IGNORED——非关注类型不写 inbox，镜像 identity 语义）。 */
    public Mono<Outcome> process(ConsumerRecord<String, String> record) {
        return Mono.defer(() -> {
            EventEventEnvelope envelope = parse(record);
            if (!EVENT_TYPE.equals(envelope.eventType())) {
                return Mono.just(Outcome.IGNORED);
            }
            RewardPayload payload = parsePayload(envelope);
            String operationId = "judge-reward:" + payload.disputeId() + ":" + payload.round()
                    + ":" + payload.judgeAccountId();
            Mono<Outcome> work = inbox
                    .recordIfAbsent(consumerName, record, envelope.eventId(), envelope.eventType(),
                            envelope.aggregateType(), envelope.aggregateId(), envelope.payload())
                    .flatMap(inserted -> inserted
                            ? credits.awardJudgeReward(payload.judgeAccountId(), payload.credits(),
                                    "审判投票奖励（争议轮次 #" + payload.round() + "）", operationId)
                                    .doOnNext(result -> log.info(
                                            "judge reward credited judge={} dispute={} round={} credits={} balance={}",
                                            payload.judgeAccountId(), payload.disputeId(), payload.round(),
                                            payload.credits(), result.balance()))
                                    .thenReturn(Outcome.PROCESSED)
                            : Mono.just(Outcome.DUPLICATE));
            return transactions.transactional(work);
        });
    }

    private EventEventEnvelope parse(ConsumerRecord<String, String> record) {
        if (record == null || record.value() == null || record.value().isBlank()) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward event value must contain valid JSON");
        }
        JsonNode root;
        try {
            root = mapper.readTree(record.value());
        } catch (Exception error) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward event value must contain valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward event envelope must be a JSON object");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward event payload must be a JSON object");
        }
        return new EventEventEnvelope(
                requiredText(root, "eventId"),
                requiredText(root, "eventType"),
                requiredText(root, "aggregateType"),
                requiredText(root, "aggregateId"),
                payload);
    }

    private RewardPayload parsePayload(EventEventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        JsonNode creditsNode = payload.get("credits");
        if (creditsNode == null || !creditsNode.isInt() || creditsNode.asInt() <= 0) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward payload field credits must be a positive integer");
        }
        return new RewardPayload(
                requiredText(payload, "disputeId"),
                payload.get("round").asInt(),
                requiredText(payload, "judgeAccountId"),
                creditsNode.asInt());
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new FinanceInboxRepository.FinanceInboxContractException(
                    "judge reward event field " + field + " must be a non-blank string");
        }
        return value.asText();
    }

    private record EventEventEnvelope(
            String eventId, String eventType, String aggregateType, String aggregateId, JsonNode payload) {}

    private record RewardPayload(String disputeId, int round, String judgeAccountId, int credits) {}

    /** 处理结局（对齐 identity NotificationProcessingResult 语义，供指标区分）。 */
    public enum Outcome {
        PROCESSED, DUPLICATE, IGNORED
    }
}
