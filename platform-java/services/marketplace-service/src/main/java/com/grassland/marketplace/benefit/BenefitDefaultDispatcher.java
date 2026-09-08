package com.grassland.marketplace.benefit;

import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 体验权益失约派发器（任务书 #96 C96-03 / D96-05）：
 * <ol>
 *   <li>回应窗到期未回应 → 自动成立（guarded 单边胜出 + 截止顺延 + 事件同事务）；</li>
 *   <li>已成立行的押金全退腿持续重放（finance 幂等），24h 重放窗内收敛，超窗转人工对账。</li>
 * </ol>
 * 失约主张行本身就是 durable intent（无独立派发标记列）；扫描谓词 + 行级守卫保证多实例安全。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.engagement", name = "benefit-dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class BenefitDefaultDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BenefitDefaultDispatcher.class);

    private final ExperienceBenefitRepository benefits;
    private final TaskApplicationRepository apps;
    private final ExperienceBenefitService benefitService;
    private final int batchSize;

    public BenefitDefaultDispatcher(ExperienceBenefitRepository benefits,
                                    TaskApplicationRepository apps,
                                    ExperienceBenefitService benefitService,
                                    @Value("${marketplace.engagement.benefit-dispatcher-batch-size:32}") int batchSize) {
        this.benefits = benefits;
        this.apps = apps;
        this.benefitService = benefitService;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${marketplace.engagement.benefit-dispatcher-poll-ms:5000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    void dispatchBatch() {
        List<ExperienceBenefit> establishable = benefits.findDefaultEstablishable(batchSize).collectList().block();
        if (establishable != null) {
            for (ExperienceBenefit benefit : establishable) {
                try {
                    benefitService.establishDefault(benefit.applicationId()).block();
                } catch (RuntimeException failure) {
                    log.warn("benefit default establish failed application={}", benefit.applicationId(), failure);
                }
            }
        }
        List<ExperienceBenefit> refundable = benefits.findDefaultRefundReplayable(batchSize).collectList().block();
        if (refundable != null) {
            for (ExperienceBenefit benefit : refundable) {
                try {
                    apps.findById(benefit.applicationId())
                            .flatMap(benefitService::refundDefaultedDeposit)
                            .switchIfEmpty(Mono.empty())
                            .block();
                } catch (RuntimeException failure) {
                    log.warn("benefit default refund replay failed application={}", benefit.applicationId(), failure);
                }
            }
        }
    }
}
