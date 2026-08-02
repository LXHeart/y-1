package com.grassland.marketplace.ops;

import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 死信登记（GL-P1-OPS-001 Stage 2）：消息行 + 处置单在<b>同一事务</b>内落库。
 *
 * <p>分开提交会出现「死信登记了但没有处置单」（运营队列里看不到）或反之（case 指向不存在的消息）。
 * 位点唯一键让重复消费返回 empty，此时不再开单也不再写审计 —— 同 {@link OpsCaseRegistrar} 的口径。
 */
@Component
public class OpsDltRegistrar {

    private final OpsDltMessageRepository messages;
    private final OpsCaseRegistrar cases;
    private final TransactionalOperator transactions;

    public OpsDltRegistrar(OpsDltMessageRepository messages, OpsCaseRegistrar cases,
                           TransactionalOperator transactions) {
        this.messages = messages;
        this.cases = cases;
        this.transactions = transactions;
    }

    public Mono<OpsDltMessage> register(String topic, int partition, long offset, String originalTopic,
                                       String messageKey, String payload, String errorSummary) {
        return transactions.transactional(
                messages.insertIfAbsent(topic, partition, offset, originalTopic, messageKey,
                                payload, errorSummary)
                        .flatMap(message -> cases.register(OpsCaseSource.DLT_MESSAGE, message.position(),
                                        null, null, originalTopic)
                                .thenReturn(message))
                        // 重复消费：位点已登记，什么都不做（幂等）。
                        .switchIfEmpty(Mono.defer(() -> messages.findByPosition(topic, partition, offset))));
    }
}
