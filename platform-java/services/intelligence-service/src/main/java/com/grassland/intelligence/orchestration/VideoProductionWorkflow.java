package com.grassland.intelligence.orchestration;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowMethod;

/**
 * 视频成片管线 workflow（任务书 #66 卡A1 契约，§3 照抄不得修改）：
 * generating →（signal 等待）→ composing → settle。
 *
 * <p>workflow 只是「驱动器」：所有状态真相在 video_production_task / take / audio 行上，
 * activity 走 #64/#65 既有领单租约协议，与 legacy worker 天然互斥共存（灰度期双跑不打架）。
 */
@WorkflowInterface
public interface VideoProductionWorkflow {
  @WorkflowMethod void run(VideoTaskSpec spec);              // generating→(signal 等待)→composing→settle
  @SignalMethod   void submitSelections(SelectionPayload selections); // 对应 #64 select 端点
  @SignalMethod   void requestReroll(String shotId);                  // 对应 #65 reroll
  @SignalMethod   void cancel(String reason);
  @QueryMethod    VideoTaskState queryState();
}
