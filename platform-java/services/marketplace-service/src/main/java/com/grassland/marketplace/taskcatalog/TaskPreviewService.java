package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.milestone.EngagementMilestoneService;
import com.grassland.marketplace.workflow.saga.SettlementWindowPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 发布预览读模型（任务书 #96 C96-05 / §6 GET /api/tasks/{id}/preview）：
 * 「做什么 / 何时交付 / 审稿几次 / 到手金额 / 可提现时间 / 取消怎么算」六段完整合作条款，
 * 全部服务端同源计算（预览数字 = 结算口径），前端只渲染不复算钱。
 *
 * <ul>
 *   <li>到手/可提现：复用结算窗口口径——确认后 max(T+N 结算权益, 争议保护窗口)（C11/D06 同源，
 *       {@link SettlementWindowPolicy}）；阶梯佣金任务按档计酬，预览列各档要点。</li>
 *   <li>取消处理：合同取消条款优先于全局模板（与取消/超时结算同一解析，C96-02/C96-04）。</li>
 *   <li>期限：合同天数优先于配置缺省（与 accept 快照同一解析，C96-01/C96-04）。</li>
 * </ul>
 */
@Component
public class TaskPreviewService {

    private final EngagementMilestoneService milestoneService;
    private final EngagementDeliveryPolicy deliveryPolicy;
    private final long settlementDaySeconds;
    private final long disputeWindowSeconds;
    private final long reviewWindowHours;
    private final int reviseCap;
    private final long resubmitHours;

    public TaskPreviewService(EngagementMilestoneService milestoneService,
                              EngagementDeliveryPolicy deliveryPolicy,
                              @Value("${marketplace.settlement.day-seconds:86400}") long settlementDaySeconds,
                              @Value("${marketplace.settlement.dispute-window-seconds:172800}") long disputeWindowSeconds,
                              @Value("${marketplace.engagement.review-window-hours:72}") long reviewWindowHours,
                              @Value("${marketplace.confirmation.supplement-cap:2}") int reviseCap,
                              @Value("${marketplace.engagement.draft-resubmit-hours:48}") long resubmitHours) {
        this.milestoneService = milestoneService;
        this.deliveryPolicy = deliveryPolicy;
        this.settlementDaySeconds = settlementDaySeconds;
        this.disputeWindowSeconds = disputeWindowSeconds;
        this.reviewWindowHours = reviewWindowHours;
        this.reviseCap = reviseCap;
        this.resubmitHours = resubmitHours;
    }

    /** 预览读模型（task 已加载；可见性由控制器守卫）。 */
    public Mono<Map<String, Object>> preview(Task task) {
        return milestoneService.effectiveCancelBps(task).map(cancelBps -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", task.id());
            m.put("title", task.title());
            m.put("status", task.status());

            Map<String, Object> what = new LinkedHashMap<>();
            what.put("platform", task.platform());
            what.put("contentForm", task.contentForm());
            what.put("description", task.description());
            what.put("productServiceInfo", task.requirements().productServiceInfo());
            what.put("mustInclude", task.requirements().mustInclude());
            what.put("forbiddenContent", task.requirements().forbiddenContent());
            what.put("metricRequirements", task.requirements().metricRequirements());
            what.put("evidenceRequirements", task.requirements().evidenceRequirements());
            what.put("publishStartAt", task.requirements().publishStartAt());
            what.put("publishEndAt", task.requirements().publishEndAt());
            m.put("what", what);

            // 何时交付：合同天数优先于配置缺省（与 accept 快照同一解析）
            boolean contractDays = task.deliveryDeadlineDays() != null;
            long effectiveDays = deliveryPolicy.contractFor(task).deliverySeconds() / 86400L;
            Map<String, Object> delivery = new LinkedHashMap<>();
            delivery.put("deliveryDeadlineDays", effectiveDays);
            delivery.put("source", contractDays ? "contract" : "default");
            delivery.put("applicationDeadline", task.applicationDeadline() == null ? null
                    : task.applicationDeadline().toString());
            m.put("delivery", delivery);

            Map<String, Object> review = new LinkedHashMap<>();
            review.put("required", task.requiresReview());
            review.put("reviewWindowHours", reviewWindowHours);
            review.put("reviseCap", reviseCap);
            review.put("resubmitHours", resubmitHours);
            review.put("timeoutPolicy", "审稿超时转人工复核，未经批准不可发布");
            m.put("review", review);

            Map<String, Object> payout = new LinkedHashMap<>();
            payout.put("mode", payoutMode(task));
            payout.put("bountyCents", task.bountyCents() == null ? 0L : task.bountyCents());
            payout.put("freebieDepositCents", task.freebieDepositCents() == null ? 0L : task.freebieDepositCents());
            payout.put("ladder", task.requirements().commissionLadder() == null ? null
                    : task.requirements().commissionLadder());
            CommissionLadder ladder = task.requirements().commissionLadder();
            Long estimatedPayout = switch (payoutMode(task)) {
                case "commerce", "ladder" -> null;
                case "freebie" -> task.freebieDepositCents();
                default -> task.bountyCents() == null ? 0L : task.bountyCents();
            };
            payout.put("estimatedPayoutCents", estimatedPayout);
            Long maximumPayout = estimatedPayout;
            if (ladder != null) {
                maximumPayout = CommissionSettlementPlan.evaluate(ladder, ladder.tiers().getLast().threshold(),
                        task.bountyCents()).settlementAmountCents();
            }
            payout.put("maximumPayoutCents", maximumPayout);
            // 可提现时间 = 确认 + max(T+N 结算权益, 争议保护窗口)（C11/D06 同源；等级按标准 T+2 预估）
            long withdrawableSeconds = SettlementWindowPolicy.windowSeconds(
                    (Integer) null, settlementDaySeconds, disputeWindowSeconds);
            payout.put("withdrawableAfterConfirmSeconds", "commerce".equals(payoutMode(task)) ? null : withdrawableSeconds);
            payout.put("withdrawablePolicy", "commerce".equals(payoutMode(task))
                    ? "按订单核销后的冷静期及退款、争议状态结算，以订单条款为准"
                    : "按标准 T+2 权益预估：确认后至少 " + durationLabel(withdrawableSeconds)
                            + "，无争议且结算完成后可提现；实际以接受时冻结的等级权益和结算状态为准");
            m.put("payout", payout);

            Map<String, Object> cancel = new LinkedHashMap<>();
            cancel.put("scriptBps", cancelBps.scriptBps());
            cancel.put("deliverableBps", cancelBps.deliverableBps());
            cancel.put("publishedBps", cancelBps.publishedBps());
            cancel.put("source", cancelBps.source());
            cancel.put("cap", "commerce".equals(payoutMode(task))
                    ? "结束推广不撤销既有订单佣金，退款与争议按订单条款处理"
                    : "freebie".equals(payoutMode(task))
                            ? "体验任务没有赏金阶段补偿；商家失约成立后押金全退，推荐官可无责退出"
                            : "补偿按已确认阶段累加，上限为已保障赏金；无确认里程碑取消，预留赏金全额退回商家");
            m.put("cancel", cancel);

            m.put("highlights", highlights(task, effectiveDays));
            return m;
        });
    }

    /** 三个模板各自显示要点（TC96-020）：按付费方式与合同条款列要点，全部服务端产出。 */
    private List<String> highlights(Task task, long deliveryDays) {
        List<String> highlights = new ArrayList<>();
        highlights.add("做什么：" + platformLabel(task.platform()) + " "
                + contentFormLabel(task.contentForm()) + "，接受报名后 " + deliveryDays + " 天内交付");
        if (task.requiresReview()) {
            highlights.add("审稿：合同要求发布前审稿，商家 " + reviewWindowHours + " 小时内批/退（最多退改 "
                    + reviseCap + " 次），批准后再发布");
        } else {
            highlights.add("审稿：本任务不要求发布前审稿，发布后提交凭证核验");
        }
        switch (payoutMode(task)) {
            case "commerce" -> highlights.add("套餐推广：通过专属链接推广，佣金按订单冻结的套餐版本结算，无需提交普通发布凭证");
            case "freebie" -> highlights.add("体验合作：押金达标全额返还；权益兑现和商家失约按体验权益单记录处理");
            case "ladder" -> highlights.add("到手：阶梯佣金按已达最高档结算（不累加），确认后按结算窗口出账");
            default -> highlights.add("到手：赏金全额托管，确认履约后按结算窗口出账");
        }
        return highlights;
    }

    private static String durationLabel(long seconds) {
        if (seconds % 86400 == 0) return seconds / 86400 + " 天";
        if (seconds % 3600 == 0) return seconds / 3600 + " 小时";
        return seconds + " 秒";
    }

    private String payoutMode(Task task) {
        if (task.commercePackageId() != null) return "commerce";
        if (task.freebieDepositCents() != null && task.freebieDepositCents() > 0) {
            return "freebie";
        }
        if (task.requirements().commissionLadder() != null) {
            return "ladder";
        }
        return "bounty";
    }

    private static String platformLabel(String platform) {
        return platform == null ? "平台" : switch (platform) {
            case "xiaohongshu" -> "小红书";
            case "douyin" -> "抖音";
            case "kuaishou" -> "快手";
            case "wechat-channels" -> "视频号";
            case "wechat-official" -> "公众号";
            case "zhihu" -> "知乎";
            case "bilibili" -> "B站";
            case "dianping" -> "大众点评";
            case "wechat-moments" -> "朋友圈";
            default -> platform;
        };
    }

    private static String contentFormLabel(String contentForm) {
        return contentForm == null ? "内容" : switch (contentForm) {
            case "image" -> "图文种草";
            case "video" -> "视频种草";
            case "article" -> "文章";
            case "interaction" -> "点赞互动";
            default -> contentForm;
        };
    }
}
