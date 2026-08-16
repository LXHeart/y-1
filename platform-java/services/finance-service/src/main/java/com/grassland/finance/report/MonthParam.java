package com.grassland.finance.report;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 月度报表参数解析（任务书 #29+#30 D2）。
 *
 * <p>月切按北京时间（Asia/Shanghai）自然月：{@code month=YYYY-MM} 由后端展开为 {@code [start, end)} 瞬时区间，
 * 前端不传时间戳。UTC 切月会把北京时间 31 号晚上的流水算进下月，故展开必须在北京时区做。
 * 非法格式 → {@link IllegalArgumentException}（全局错误处理器映射 400）。
 */
public final class MonthParam {

    /** 业务月切时区（D2）：报表口径一律北京时间自然月。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private MonthParam() {
    }

    /** 解析 {@code YYYY-MM}；非法格式抛 IllegalArgumentException（→400）。 */
    public static YearMonth parse(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " 必填，格式 YYYY-MM");
        }
        try {
            return YearMonth.parse(value.trim(), MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " 格式非法，应为 YYYY-MM：" + value);
        }
    }

    /** 北京时区自然月展开为 {@code [start, end)} 瞬时区间。 */
    public record MonthRange(Instant start, Instant end) {
    }

    public static MonthRange range(YearMonth month) {
        ZonedDateTime start = month.atDay(1).atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = month.plusMonths(1).atDay(1).atStartOfDay(BUSINESS_ZONE);
        return new MonthRange(start.toInstant(), end.toInstant());
    }
}
