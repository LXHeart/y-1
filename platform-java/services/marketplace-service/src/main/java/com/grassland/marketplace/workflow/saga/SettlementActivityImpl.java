package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/**
 * 结算窗口 Saga 活动实现（草场 Epic 5 Slice 5A / HLD 9.2、10.3）。
 *
 * <p>窗口到期后执行（幂等 + 重验）：重验 accepted+confirmed → 委托 {@link SettlementExecution} 查争议/核验 +
 * capture/hold。capture/hold 钱侧逻辑抽到 {@code SettlementExecution}，与 D-03 自动结算（{@code ConfirmationActivityImpl}）共用。
 */
@Component
@ActivityImpl(workers = ApplicationReservationWorkflowImpl.TASK_QUEUE)
public class SettlementActivityImpl implements SettlementActivity {

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final SettlementExecution settlementExecution;

    public SettlementActivityImpl(TaskApplicationRepository apps, TaskRepository tasks,
                                  SettlementExecution settlementExecution) {
        this.apps = apps;
        this.tasks = tasks;
        this.settlementExecution = settlementExecution;
    }

    @Override
    public SettlementOutcome captureSettlement(SettlementInput input) {
        TaskApplication app = apps.findById(input.applicationId()).block();
        if (app == null) {
            return SettlementOutcome.aborted();
        }
        if (!"accepted".equals(app.status()) || app.confirmedAt() == null) {
            return SettlementOutcome.aborted();  // 非 accepted+confirmed（被回退/未确认/已结算）
        }
        // 任务归属用于通知收件人解析（Slice 12 Stage 3）；任务缺失则置空，不阻断结算主路径。
        Task task = tasks.findById(app.taskId()).block();
        String taskOwnerId = task == null ? null : task.ownerAccountId();
        return settlementExecution.captureOrHold(input.organizationId(), input.applicationId(), app, taskOwnerId);
    }
}
