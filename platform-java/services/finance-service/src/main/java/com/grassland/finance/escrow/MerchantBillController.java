package com.grassland.finance.escrow;

import com.grassland.finance.report.MonthParam;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import com.grassland.reporting.ReportFormat;
import com.grassland.reporting.ReportRenderer;
import com.grassland.reporting.TabularReport;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家月度账单（任务书 #29+#30 #30）。
 *
 * <p>{@code GET /api/finance/organizations/{orgId}/monthly-bill?month=YYYY-MM} — 按月汇总资金流水。
 * org-scoped：路径收 orgId + {@code requireMerchant} + {@code caller.organizationId==orgId} 资源自查
 * （镜像 {@code AccountController.balance}，HLD 7.4），跨 org → 404 防探测。
 *
 * <p>科目中文 label 在本响应内给出（前端直接展示，不再映射——任务书约定「选一处，别两处」）。
 */
@RestController
public class MerchantBillController {

    /** journal_type → 中文科目名（D4；前端直接渲染 label，不做二次映射）。 */
    private static final Map<String, String> FLOW_LABELS = Map.ofEntries(
            Map.entry("DEPOSIT", "充值"),
            Map.entry("RESERVE", "预留"),
            Map.entry("RELEASE", "释放"),
            Map.entry("CAPTURE", "结算"),
            Map.entry("REVERSE", "冲正"),
            Map.entry("WITHDRAW", "提现"),
            Map.entry("CONSUMER_PAYMENT", "消费者支付"),
            Map.entry("CONSUMER_REFUND", "消费者退款"),
            Map.entry("CONSUMER_SPLIT", "核销分账"),
            Map.entry("AI_CREDIT_PURCHASE", "AI 积分购买"),
            Map.entry("OPENING", "期初余额"));

    private final FinanceCallerResolver callers;
    private final MerchantBillRepository bills;

    public MerchantBillController(FinanceCallerResolver callers, MerchantBillRepository bills) {
        this.callers = callers;
        this.bills = bills;
    }

    @GetMapping("/api/finance/organizations/{orgId}/monthly-bill")
    public Mono<ResponseEntity<Map<String, Object>>> monthlyBill(
            @PathVariable String orgId, @RequestParam String month, ServerHttpRequest request) {
        YearMonth yearMonth = MonthParam.parse(month, "month");
        MonthParam.MonthRange range = MonthParam.range(yearMonth);
        return callers.requireMerchant(request)
                .flatMap(caller -> {
                    // 跨 org 一律 404（不是 403）：不暴露「该组织存在」这一信息（防探测）。
                    if (!orgId.equals(caller.organizationId())) {
                        return Mono.<ResponseEntity<Map<String, Object>>>error(
                                new FinanceException(404, "账单不存在"));
                    }
                    return bills.flows(orgId, range.start(), range.end()).collectList()
                            .zipWith(bills.platformFee(orgId, range.start(), range.end()))
                            .map(tuple -> {
                                List<MerchantBillRepository.JournalFlow> flows = tuple.getT1();
                                long platformFeeCents = tuple.getT2();
                                long netEscrowDeltaCents = flows.stream()
                                        .mapToLong(MerchantBillRepository.JournalFlow::escrowNetCents).sum();
                                Map<String, Object> map = new LinkedHashMap<>();
                                map.put("month", yearMonth.toString());
                                map.put("flows", flows.stream().map(MerchantBillController::flowBody).toList());
                                map.put("platformFeeCents", platformFeeCents);
                                map.put("netEscrowDeltaCents", netEscrowDeltaCents);
                                return ResponseEntity.ok(Map.of("success", true, "data", map));
                            });
                });
    }

    @GetMapping("/api/finance/organizations/{orgId}/monthly-bill/export")
    public Mono<ResponseEntity<byte[]>> exportMonthlyBill(
            @PathVariable String orgId, @RequestParam String month,
            @RequestParam(defaultValue = "csv") String format, ServerHttpRequest request) {
        YearMonth yearMonth = MonthParam.parse(month, "month");
        MonthParam.MonthRange range = MonthParam.range(yearMonth);
        ReportFormat reportFormat = ReportFormat.parse(format);
        return callers.requireMerchant(request)
                .flatMap(caller -> {
                    if (!orgId.equals(caller.organizationId())) {
                        return Mono.<ResponseEntity<byte[]>>error(new FinanceException(404, "账单不存在"));
                    }
                    return bills.flows(orgId, range.start(), range.end()).collectList()
                            .zipWith(bills.platformFee(orgId, range.start(), range.end()))
                            .map(tuple -> {
                                List<MerchantBillRepository.JournalFlow> flows = tuple.getT1();
                                long platformFeeCents = tuple.getT2();
                                long netEscrowDeltaCents = flows.stream()
                                        .mapToLong(MerchantBillRepository.JournalFlow::escrowNetCents).sum();
                                List<List<?>> rows = new java.util.ArrayList<>();
                                flows.forEach(flow -> rows.add(List.of(yearMonth.toString(), flow.journalType(),
                                        FLOW_LABELS.getOrDefault(flow.journalType(), flow.journalType()),
                                        flow.escrowNetCents(), "flow")));
                                rows.add(List.of(yearMonth.toString(), "PLATFORM_FEE", "平台费",
                                        platformFeeCents, "summary"));
                                rows.add(List.of(yearMonth.toString(), "NET_ESCROW_DELTA", "托管净变动",
                                        netEscrowDeltaCents, "summary"));
                                return reportResponse("monthly-bill-" + yearMonth, reportFormat,
                                        new TabularReport("Monthly Bill",
                                                List.of("month", "type", "label", "amount_cents", "row_type"), rows));
                            });
                });
    }

    private static Map<String, Object> flowBody(MerchantBillRepository.JournalFlow flow) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", flow.journalType());
        map.put("label", FLOW_LABELS.getOrDefault(flow.journalType(), flow.journalType()));
        map.put("amountCents", flow.escrowNetCents());
        return map;
    }

    private static ResponseEntity<byte[]> reportResponse(
            String filename, ReportFormat format, TabularReport report) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(format.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename + "." + format.extension()).build().toString())
                .body(ReportRenderer.render(report, format));
    }
}
