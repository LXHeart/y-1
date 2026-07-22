package com.grassland.marketplace.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** demo activity：被 workflow 调用的幂等单元（HLD 9.2：Activity 必须幂等，执行前重验状态）。 */
@ActivityInterface
public interface GrasslandDemoActivity {

    @ActivityMethod
    String prepare(String seed);

    @ActivityMethod
    String finish(String prepared);
}
