package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 价目表 admin API（V52）。全部 {@code requireAdmin}，与 {@code /api/admin/ai/models} 同闸。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/admin/ai/price-tables — 列全部版本（含 retired）</li>
 *   <li>GET    /api/admin/ai/price-tables/{id} — 版本详情 + 逐模型单价</li>
 *   <li>POST   /api/admin/ai/price-tables — 新建 draft，可从某版本复制明细</li>
 *   <li>PUT    /api/admin/ai/price-tables/{id}/models — 整份覆盖明细（仅 draft）</li>
 *   <li>POST   /api/admin/ai/price-tables/{id}/activate — 激活（旧 active 转 retired）</li>
 *   <li>DELETE /api/admin/ai/price-tables/{id} — 删 draft</li>
 * </ul>
 *
 * <p><b>只有 draft 可改</b>：active/retired 的单价必须冻结，否则存量 Run 按 label 查回来的价会变，
 * 等于篡改历史账。调价的正确路径是「复制成新 draft → 改 → 激活」。
 */
@RestController
@RequestMapping("/api/admin/ai/price-tables")
public class PriceTableAdminController {

    private final IntelligenceCallerResolver callers;
    private final PriceTableRepository repository;
    private final PriceTableService priceTableService;
    private final TransactionalOperator transactions;

    public PriceTableAdminController(
            IntelligenceCallerResolver callers,
            PriceTableRepository repository,
            PriceTableService priceTableService,
            TransactionalOperator transactions) {
        this.callers = callers;
        this.repository = repository;
        this.priceTableService = priceTableService;
        this.transactions = transactions;
    }

    @GetMapping
    public Flux<Map<String, Object>> list(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMapMany(admin -> repository.findAllVersions()
                        .map(PriceTableAdminController::versionPayload));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findVersionById(id)
                        .switchIfEmpty(Mono.error(notFound(id)))
                        .flatMap(version -> repository.findModelsByVersion(id)
                                .map(PriceTableAdminController::modelPayload)
                                .collectList()
                                .map(models -> {
                                    Map<String, Object> payload = versionPayload(version);
                                    payload.put("models", models);
                                    return ResponseEntity.ok(payload);
                                })));
    }

    /**
     * 新建 draft。{@code copyFromVersionId} 非空时复制该版本明细——调价的常规路径是
     * 「复制当前 active → 改几个数 → 激活」，从零填 9+ 个模型不现实。
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> createDraft(
            @Valid @RequestBody CreatePriceTableRequest body, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> transactions.transactional(
                        repository.createVersion(body.label(), "draft", body.note(), admin.accountId())
                                .flatMap(newId -> copyModelsIfRequested(body.copyFromVersionId(), newId)
                                        .thenReturn(newId)))
                        .flatMap(newId -> repository.findVersionById(newId))
                        .map(version -> ResponseEntity.status(201).body(versionPayload(version))))
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new IntelligenceException(409, "该 label 已存在: " + body.label()));
    }

    private Mono<Void> copyModelsIfRequested(UUID sourceVersionId, UUID targetVersionId) {
        if (sourceVersionId == null) {
            return Mono.empty();
        }
        return repository.findModelsByVersion(sourceVersionId)
                .collectList()
                .flatMap(models -> repository.replaceModels(targetVersionId, models));
    }

    @PutMapping("/{id}/models")
    public Mono<ResponseEntity<Map<String, Object>>> replaceModels(
            @PathVariable UUID id,
            @Valid @RequestBody ReplacePriceModelsRequest body,
            ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findVersionById(id)
                        .switchIfEmpty(Mono.error(notFound(id)))
                        .flatMap(version -> {
                            if (!"draft".equals(version.status())) {
                                return Mono.error(new IntelligenceException(409,
                                        "只有 draft 版本可改单价；请复制成新 draft 后修改"));
                            }
                            return transactions.transactional(
                                    repository.replaceModels(id, body.toRows()));
                        })
                        .then(repository.findVersionById(id))
                        .flatMap(version -> repository.findModelsByVersion(id)
                                .map(PriceTableAdminController::modelPayload)
                                .collectList()
                                .map(models -> {
                                    Map<String, Object> payload = versionPayload(version);
                                    payload.put("models", models);
                                    return ResponseEntity.ok(payload);
                                })));
    }

    /**
     * 激活 draft。同事务内把旧 active 转 retired，随后 {@link PriceTableService#invalidate()}
     * 让下一次估价立即读到新表（否则最多有 60 秒缓存窗口仍按旧价估）。
     */
    @PostMapping("/{id}/activate")
    public Mono<ResponseEntity<Map<String, Object>>> activate(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findVersionById(id)
                        .switchIfEmpty(Mono.error(notFound(id)))
                        .flatMap(version -> {
                            if ("active".equals(version.status())) {
                                return Mono.error(new IntelligenceException(409, "该版本已是生效状态"));
                            }
                            if (!"draft".equals(version.status())) {
                                return Mono.error(new IntelligenceException(409,
                                        "只有 draft 可激活；retired 版本必须保留以复现存量 Run 的账"));
                            }
                            return transactions.transactional(repository.activate(id));
                        })
                        .flatMap(activated -> activated
                                ? Mono.just(true)
                                : Mono.error(new IntelligenceException(409, "激活失败：该版本已不是 draft")))
                        .doOnNext(ignored -> priceTableService.invalidate())
                        .then(repository.findVersionById(id))
                        .map(version -> ResponseEntity.ok(versionPayload(version))));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deleteDraft(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(admin -> repository.findVersionById(id)
                        .switchIfEmpty(Mono.error(notFound(id)))
                        .flatMap(version -> {
                            if (!"draft".equals(version.status())) {
                                return Mono.<Boolean>error(new IntelligenceException(409,
                                        "只有 draft 可删除；active 在用、retired 要复现存量 Run 的账"));
                            }
                            return transactions.transactional(repository.deleteDraft(id));
                        })
                        .flatMap(deleted -> deleted
                                ? Mono.just(ResponseEntity.noContent().<Void>build())
                                : Mono.error(notFound(id))));
    }

    private static Map<String, Object> versionPayload(PriceTableRepository.VersionRow version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", version.id().toString());
        payload.put("label", version.label());
        payload.put("status", version.status());
        payload.put("note", version.note());
        payload.put("createdBy", version.createdBy());
        payload.put("createdAt", version.createdAt());
        payload.put("activatedAt", version.activatedAt());
        return payload;
    }

    private static Map<String, Object> modelPayload(PriceTableRepository.ModelPriceRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", row.modelId());
        payload.put("capability", row.capability());
        payload.put("provider", row.provider());
        payload.put("centsPer1kInputTokens", row.centsPer1kInputTokens());
        payload.put("centsPer1kOutputTokens", row.centsPer1kOutputTokens());
        payload.put("centsPerImage", row.centsPerImage());
        payload.put("centsPerSecond", row.centsPerSecond());
        return payload;
    }

    private static IntelligenceException notFound(UUID id) {
        return new IntelligenceException(404, "未找到价目表版本: " + id);
    }

    /** 明细整份覆盖请求；上限 500 个模型（远超实际需要，只为挡住误传巨量数据）。 */
    public record ReplacePriceModelsRequest(
            @jakarta.validation.constraints.NotNull(message = "models 必填")
            @jakarta.validation.constraints.Size(max = 500, message = "模型数不能超过 500")
            List<@Valid PriceModelEntry> models) {

        List<PriceTableRepository.ModelPriceRow> toRows() {
            return models.stream()
                    .map(entry -> new PriceTableRepository.ModelPriceRow(
                            entry.modelId().trim(), entry.capability(), entry.provider(),
                            entry.centsPer1kInputTokens() == null ? 0 : entry.centsPer1kInputTokens(),
                            entry.centsPer1kOutputTokens() == null ? 0 : entry.centsPer1kOutputTokens(),
                            entry.centsPerImage() == null ? 0 : entry.centsPerImage(),
                            entry.centsPerSecond() == null ? 0 : entry.centsPerSecond()))
                    .toList();
        }
    }

    /** 单条单价。四个维度可空（缺省 0 = 该维度免费），但不得为负。 */
    public record PriceModelEntry(
            @jakarta.validation.constraints.NotBlank(message = "modelId 必填")
            @jakarta.validation.constraints.Size(max = 128, message = "modelId 不能超过 128 字符")
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._:@/-]+",
                    message = "modelId 含非法字符")
            String modelId,
            @jakarta.validation.constraints.NotBlank(message = "capability 必填")
            @jakarta.validation.constraints.Size(max = 64) String capability,
            @jakarta.validation.constraints.NotBlank(message = "provider 必填")
            @jakarta.validation.constraints.Size(max = 64) String provider,
            @jakarta.validation.constraints.Min(value = 0, message = "单价不能为负")
            Integer centsPer1kInputTokens,
            @jakarta.validation.constraints.Min(value = 0, message = "单价不能为负")
            Integer centsPer1kOutputTokens,
            @jakarta.validation.constraints.Min(value = 0, message = "单价不能为负")
            Integer centsPerImage,
            @jakarta.validation.constraints.Min(value = 0, message = "单价不能为负")
            Integer centsPerSecond) {
    }

    /** 新建 draft 请求。{@code label} 是对外版本号，会冻结进 ai_run.price_table_version。 */
    public record CreatePriceTableRequest(
            @jakarta.validation.constraints.NotBlank(message = "label 必填")
            @jakarta.validation.constraints.Size(max = 64, message = "label 不能超过 64 字符")
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._-]+",
                    message = "label 只能含字母、数字、点、下划线、连字符")
            String label,
            @jakarta.validation.constraints.Size(max = 500, message = "note 不能超过 500 字符") String note,
            UUID copyFromVersionId) {
    }
}
