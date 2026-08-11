package com.grassland.finance.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Provider-neutral webhook inbox and statement reconciliation policy. */
@Component
public class ProviderLifecycleService {

    private final ProviderOperationRepository operations;
    private final ProviderLifecycleRepository lifecycle;
    private final TransactionalOperator transactions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProviderLifecycleService(
            ProviderOperationRepository operations, ProviderLifecycleRepository lifecycle,
            TransactionalOperator transactions) {
        this.operations = operations;
        this.lifecycle = lifecycle;
        this.transactions = transactions;
    }

    public Mono<ProviderWebhookEvent> receiveWebhook(ProviderWebhookCommand input) {
        ProviderWebhookCommand command = normalize(input);
        Mono<ProviderWebhookEvent> work = lifecycle.findWebhook(command.provider(), command.eventId())
                .switchIfEmpty(Mono.defer(() -> findOperation(command.operationId(), command.provider(),
                                command.providerRef())
                        .flatMap(operation -> processWebhook(command, operation))
                        .switchIfEmpty(lifecycle.insertWebhook(
                                command, "ignored", "未找到对应的通道操作"))));
        return transactions.transactional(work);
    }

    public Mono<ProviderReconciliation> reconcile(ProviderReconciliationCommand input) {
        ProviderReconciliationCommand command = normalize(input);
        Mono<ProviderReconciliation> work = lifecycle.findReconciliation(
                        command.provider(), command.statementRef(), command.providerRef())
                .switchIfEmpty(Mono.defer(() -> findOperation(
                                command.operationId(), command.provider(), command.providerRef())
                        .flatMap(operation -> {
                            String status = reconciliationMatches(command, operation)
                                    ? "matched" : "mismatch";
                            return lifecycle.insertReconciliation(command, status)
                                    .flatMap(result -> "matched".equals(result.status())
                                            ? operations.markStatus(operation.operationId(), "reconciled")
                                                    .thenReturn(result)
                                            : Mono.just(result));
                        })
                        .switchIfEmpty(lifecycle.insertReconciliation(command, "unmatched"))));
        return transactions.transactional(work);
    }

    private Mono<ProviderWebhookEvent> processWebhook(
            ProviderWebhookCommand command, ProviderOperation operation) {
        if (!operation.provider().equals(command.provider())
                || (command.providerRef() != null
                        && !operation.providerRef().equals(command.providerRef()))) {
            return lifecycle.insertWebhook(command, "ignored", "通道或通道引用不匹配");
        }
        String operationStatus = webhookOperationStatus(command.eventType(), operation.operationType());
        if (operationStatus == null) {
            return lifecycle.insertWebhook(command, "ignored", "不支持的 Webhook 事件类型");
        }
        if (!canTransition(operation.status(), operationStatus)) {
            return lifecycle.insertWebhook(command, "ignored", "通道操作状态不可倒退");
        }
        Mono<ProviderOperation> update = operation.status().equals(operationStatus)
                ? Mono.just(operation)
                : operations.markStatus(operation.operationId(), operationStatus);
        return update.then(lifecycle.insertWebhook(command, "processed", null));
    }

    private Mono<ProviderOperation> findOperation(
            String operationId, String provider, String providerRef) {
        if (!blank(operationId)) {
            return operations.findByOperationId(operationId);
        }
        if (!blank(providerRef)) {
            return operations.findByProviderRef(provider, providerRef);
        }
        return Mono.empty();
    }

    private static boolean reconciliationMatches(
            ProviderReconciliationCommand command, ProviderOperation operation) {
        return operation.provider().equals(command.provider())
                && operation.providerRef().equals(command.providerRef())
                && (blank(command.operationId()) || operation.operationId().equals(command.operationId()))
                && (blank(command.operationType())
                        || operation.operationType().equals(command.operationType()))
                && operation.amountCents() == command.amountCents()
                && operation.currency().equals(command.currency())
                && ("succeeded".equals(operation.status()) || "reconciled".equals(operation.status()));
    }

    private static boolean canTransition(String current, String target) {
        if (current.equals(target)) {
            return true;
        }
        return switch (current) {
            case "requested" -> "processing".equals(target)
                    || "succeeded".equals(target) || "failed".equals(target);
            case "processing" -> "succeeded".equals(target) || "failed".equals(target);
            default -> false;
        };
    }

    private static String webhookOperationStatus(String eventType, String operationType) {
        String normalized = eventType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('.');
        String prefix = separator < 0 ? "operation" : normalized.substring(0, separator);
        String outcome = separator < 0 ? normalized : normalized.substring(separator + 1);
        if (!"operation".equals(prefix) && !operationType.equals(prefix)) {
            return null;
        }
        return switch (outcome) {
            case "requested" -> "requested";
            case "processing" -> "processing";
            case "succeeded" -> "succeeded";
            case "failed" -> "failed";
            default -> null;
        };
    }

    private ProviderWebhookCommand normalize(ProviderWebhookCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Webhook 请求不能为空");
        }
        if (blank(command.eventId()) || blank(command.provider()) || blank(command.eventType())) {
            throw new IllegalArgumentException("Webhook eventId、provider、eventType 不能为空");
        }
        return new ProviderWebhookCommand(
                command.eventId().trim(), command.provider().trim().toLowerCase(Locale.ROOT),
                command.eventType().trim().toLowerCase(Locale.ROOT), trimToNull(command.providerRef()),
                trimToNull(command.operationId()), normalizeJson(command.payloadJson()));
    }

    private ProviderReconciliationCommand normalize(ProviderReconciliationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("对账请求不能为空");
        }
        if (blank(command.provider()) || blank(command.statementRef()) || blank(command.providerRef())
                || command.amountCents() < 0) {
            throw new IllegalArgumentException("对账 provider、statementRef、providerRef 或金额不合法");
        }
        String currency = blank(command.currency()) ? "CNY" : command.currency().trim().toUpperCase(Locale.ROOT);
        return new ProviderReconciliationCommand(
                command.provider().trim().toLowerCase(Locale.ROOT), command.statementRef().trim(),
                command.providerRef().trim(), trimToNull(command.operationId()),
                lowerToNull(command.operationType()), command.amountCents(), currency,
                normalizeJson(command.payloadJson()));
    }

    private String normalizeJson(String value) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(blank(value) ? "{}" : value));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("payloadJson 必须是合法 JSON", error);
        }
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String lowerToNull(String value) {
        return blank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
