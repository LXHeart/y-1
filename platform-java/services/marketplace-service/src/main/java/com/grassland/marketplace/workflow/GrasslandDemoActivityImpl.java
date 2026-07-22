package com.grassland.marketplace.workflow;

import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

/**
 * demo activity 实现：纯函数式聚合（验证幂等/retry——相同输入恒定输出）。
 *
 * <p>真实业务 activity 会调领域 Command/写库（HLD：Activity 幂等、执行前重验），本轮 demo 不涉及。
 */
@Component
@ActivityImpl(workers = GrasslandDemoWorkflowImpl.TASK_QUEUE)
public class GrasslandDemoActivityImpl implements GrasslandDemoActivity {

    @Override
    public String prepare(String seed) {
        return "prepared:" + seed;
    }

    @Override
    public String finish(String prepared) {
        return prepared + "|finished";
    }
}
