package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.benefit.ExperienceBenefit;
import com.grassland.marketplace.benefit.ExperienceBenefitRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 下一步只从服务端履约事实派生，不引入 application 状态或资金命令。 */
@Component
public class EngagementActionContract {
    private final SubmissionRepository submissions;
    private final ExperienceBenefitRepository benefits;
    private final EngagementExtensionRepository extensions;
    private final long reviewSeconds;
    private final long resubmitSeconds;

    public EngagementActionContract(SubmissionRepository submissions, ExperienceBenefitRepository benefits,
            EngagementExtensionRepository extensions,
            @Value("${marketplace.engagement.review-window-hours:72}") long reviewHours,
            @Value("${marketplace.engagement.draft-resubmit-hours:48}") long resubmitHours) {
        this.submissions = submissions;
        this.benefits = benefits;
        this.extensions = extensions;
        this.reviewSeconds = Math.max(1, reviewHours * 3600L);
        this.resubmitSeconds = Math.max(1, resubmitHours * 3600L);
    }

    public record Next(String group, String label, String blockedReason, Instant dueAt,
                       String benefitStatus) {
        void appendTo(Map<String, Object> contract) {
            contract.put("nextActionGroup", group);
            contract.put("nextActionLabel", label);
            contract.put("blockedReason", blockedReason);
            contract.put("nextActionDueAt", dueAt == null ? null : dueAt.toString());
            contract.put("benefitStatus", benefitStatus);
        }
    }

    public Mono<Next> read(Task task, TaskApplication app, boolean manager, String settlementStatus,
                           String holdReason, Instant settlementDueAt) {
        return Mono.zip(submissions.findByApplication(app.id()).collectList(),
                        benefits.findByApplication(app.id()).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        extensions.findPending(app.id()).hasElement())
                .map(facts -> derive(task, app, manager, settlementStatus, holdReason, settlementDueAt,
                        facts.getT1(), facts.getT2().orElse(null), facts.getT3(), Instant.now()));
    }

    Next derive(Task task, TaskApplication app, boolean manager, String settlementStatus,
                String holdReason, Instant settlementDueAt, List<EngagementSubmission> history,
                ExperienceBenefit benefit, boolean extensionPending, Instant now) {
        String benefitStatus = benefit == null ? null : benefit.status();
        if ("settled".equals(settlementStatus)) return next("completed", "完成", null, null, benefitStatus);
        if (!List.of("pending", "reconsent", "reserving", "accepted").contains(app.status())) {
            return next("ended", "已结束", "该合作已结束", null, benefitStatus);
        }
        if ("held".equals(settlementStatus) || app.contestRequestedAt() != null) {
            return next("exception", "处理争议或资金异常", holdReason == null ? "争议处理中" : holdReason,
                    null, benefitStatus);
        }
        if (app.confirmedAt() != null) {
            return next("observation", "观察期", "等待结算窗口与争议处理完成", settlementDueAt, benefitStatus);
        }
        if ("pending".equals(app.status())) {
            return next(manager ? "selection" : "waiting", manager ? "待筛选" : "等待商家筛选",
                    manager ? null : "等待商家处理报名", task.applicationDeadline(), benefitStatus);
        }
        if ("reconsent".equals(app.status())) {
            return next("reconsent", manager ? "等待确认新条款" : "确认新条款",
                    manager ? "等待推荐官确认" : null, task.applicationDeadline(), benefitStatus);
        }
        if ("reserving".equals(app.status())) {
            return next("waiting", "资金预留中", "等待资金预留完成", null, benefitStatus);
        }
        if (task.isCommercePromotion()) return next("promotion", "推广套餐", null, null, benefitStatus);
        if (benefit != null && benefit.defaultClaimOpen()) {
            return next("benefit_default", manager ? "回应体验失约主张" : "等待失约回应",
                    manager ? null : "失约回应期间交付计时暂停", benefit.defaultDeadlineAt(), benefitStatus);
        }
        if (benefit != null && ExperienceBenefit.STATUS_MERCHANT_DEFAULTED.equals(benefit.status())) {
            return next("exit", manager ? "处理体验失约" : "可无责退出", "商家失约已成立", null, benefitStatus);
        }
        if (extensionPending) {
            return next("extension", manager ? "审批延期" : "等待延期审批",
                    manager ? null : "延期获批前原交付期限仍有效", app.deliveryDeadlineAt(), benefitStatus);
        }
        EngagementSubmission pending = history.stream().filter(EngagementSubmission::isPending).findFirst().orElse(null);
        if (pending != null) {
            if (pending.isDraft()) {
                Instant due = pending.createdAt().plusSeconds(reviewSeconds);
                boolean overdue = !due.isAfter(now);
                return next(overdue ? "manual_review" : "draft_review",
                        overdue ? "审稿超时待复核" : manager ? "待审稿" : "等待商家审稿",
                        manager && !overdue ? null : "草稿获批后才可发布", due, benefitStatus);
            }
            return next("acceptance", manager ? "待验收" : "等待商家验收",
                    manager ? null : "已提交凭证，等待验收", app.merchantConfirmDeadlineAt(), benefitStatus);
        }
        EngagementSubmission latest = history.isEmpty() ? null : history.getFirst();
        if (latest != null && "rejected".equals(latest.status())) {
            Instant due = latest.isDraft() && latest.reviewedAt() != null
                    ? latest.reviewedAt().plusSeconds(resubmitSeconds) : app.deliveryDeadlineAt();
            boolean expired = latest.isDraft() && (due == null || due.isBefore(now));
            return next(expired ? "exception" : "revision",
                    expired ? "补交超时，处理争议或退出" : manager ? "等待修改" : "待修改",
                    expired ? "草稿补交期限已过" : manager ? "等待推荐官修改" : null, due, benefitStatus);
        }
        if (benefit != null && (ExperienceBenefit.STATUS_BOOKED.equals(benefit.status())
                || ExperienceBenefit.STATUS_PENDING_BOOKING.equals(benefit.status())
                || (benefit.consumed() && benefit.fulfilledConfirmedBy() == null))) {
            return next("benefit", manager ? "待供样或接待确认" : "待体验权益兑现",
                    null, benefit.bookingWindow(), benefitStatus);
        }
        if (benefit == null && task.freebieDepositCents() != null && task.freebieDepositCents() > 0) {
            return next("benefit", manager ? "等待体验预约" : "预约体验权益",
                    manager ? "等待推荐官预约" : null, app.deliveryDeadlineAt(), "pending_booking");
        }
        Instant due = app.deliveryDeadlineAt();
        boolean remedy = due != null && !due.isAfter(now);
        boolean approvedDraft = history.stream().anyMatch(s -> s.isDraft() && "accepted".equals(s.status()));
        return next("delivery", manager ? "等待交付" : task.requiresReview() && !approvedDraft ? "待交草稿" : "待交付",
                manager ? "等待推荐官交付" : remedy ? "交付已逾期，请在补救期限内处理" : null,
                remedy ? app.remedyDeadlineAt() : due, benefitStatus);
    }

    private static Next next(String group, String label, String reason, Instant due, String benefit) {
        return new Next(group, label, reason, due, benefit);
    }
}
