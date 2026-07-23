package com.grassland.identity.permission;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 商家权限审核 SLA（HLD D-05「审核时效」）。**仅跟踪+展示**——提交时算 deadline，读时算 slaStatus；
 * 不做超时自动批准（安全顾虑：自动提权，留配置位默认关）。
 *
 * <p>{@code slaStatus}：终态→completed；非终态且 now>deadline→overdue；now>deadline-atRisk→at_risk；否则 within。
 * 窗口由 {@code identity.permission.sla-hours}（默认 72）与 {@code at-risk-hours}（默认 12）配置。
 */
@Component
public class PermissionSla {

    private final Duration sla;
    private final Duration atRisk;

    public PermissionSla(@Value("${identity.permission.sla-hours:72}") long slaHours,
                         @Value("${identity.permission.at-risk-hours:12}") long atRiskHours) {
        this.sla = Duration.ofHours(slaHours <= 0 ? 72 : slaHours);
        this.atRisk = Duration.ofHours(Math.max(0, atRiskHours));
    }

    public Instant deadlineFor(Instant submittedAt) {
        return submittedAt.plus(sla);
    }

    public String status(PermissionRequestStatus requestStatus, Instant deadline, Instant now) {
        if (requestStatus == null) {
            return "unknown";
        }
        if (requestStatus.isTerminal()) {
            return "completed";
        }
        if (deadline == null) {
            return "unknown";
        }
        Instant when = now == null ? Instant.now() : now;
        if (when.isAfter(deadline)) {
            return "overdue";
        }
        if (when.isAfter(deadline.minus(atRisk))) {
            return "at_risk";
        }
        return "within";
    }
}
