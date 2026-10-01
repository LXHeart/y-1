// fix2-c24.test.ts — 107-fix-2 C107F2-24：Feedback 批次校验和落盘原子化（F32/§6.11）。
//
// TC-F2-24-01 整批原子：空文档 + 两个合法 add 同 expectedHash 一次提交——两条落盘、
//            hash 更新、重读一致。
// TC-F2-24-02 整批拒绝：第一条合法 add、第二条 unknown operation → 失败且 comments
//            仍 0、文件 hash/字节完全不变。
// TC-F2-24-03 并发屏障：两个请求同 expectedHash 竞争——恰一批成功、另一 409
//            （feedback_conflict 携当前 hash），无交织部分结果。
// TC-F2-24-04 幂等重放：同 requestId 重发返回原回执、评论数不增。
//
// 依赖真实 G 发行版（上游 readFeedbackMutation/readFeedbackDocument 语义）。
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { strict as assert } from "node:assert";
import test from "node:test";

const generatedRoot = join(import.meta.dirname, "../../../.generated/hypit");
const { feedbackHash, mutateFeedback, resetFeedbackReceiptsForTest }
  = await import("../../src/studio/feedback.ts");
const { DispatchError } = await import("../../src/commands/dispatcher.ts");

if (!existsSync(join(generatedRoot, "packages/studio/src/feedback-store.ts"))) {
  console.error(`G distribution missing at ${generatedRoot}`);
  process.exit(1);
}

const PROJECT_ID = "35191919-1919-4191-8191-191919191919";

function workspace(): { projectsRoot: string; cleanup: () => void; filePath: string } {
  const projectsRoot = mkdtempSync(join(tmpdir(), "fix2-c24-"));
    const filePath = join(projectsRoot, PROJECT_ID, "work", "FEEDBACK.json");
  mkdirSync(join(projectsRoot, PROJECT_ID, "work"), { recursive: true });
  writeFileSync(filePath, `${JSON.stringify({ comments: [] }, null, 2)}\n`);
  return { projectsRoot, cleanup: () => rmSync(projectsRoot, { recursive: true, force: true }), filePath };
}

const add = (id: string, text: string): unknown => ({
  type: "add",
  comment: { id, run: "main.svrun", at: 12, text },
});

test("TC-F2-24-01 E01 two legal adds with one expectedHash land as a single atomic commit", async () => {
  resetFeedbackReceiptsForTest();
  const ws = workspace();
  try {
    const initial = JSON.parse(readFileSync(ws.filePath, "utf8"));
    const receipt = await mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
      [add("c-1", "开场太长"), add("c-2", "字幕偏小")], feedbackHash(initial), "req-e01") as {
        applied: number; comments: { id: string }[]; hash: string;
      };
    assert.equal(receipt.applied, 2, "both operations in one commit");
    assert.equal(receipt.comments.length, 2);

    // 落盘与回执一致：重读同 hash、两条都在。
    const reread = JSON.parse(readFileSync(ws.filePath, "utf8"));
    assert.equal(reread.comments.length, 2);
    assert.equal(feedbackHash(reread), receipt.hash, "re-read hash matches the receipt");
    assert.deepEqual(reread.comments.map((comment: { id: string }) => comment.id), ["c-1", "c-2"]);
  } finally {
    ws.cleanup();
  }
});

test("TC-F2-24-02 E02 unknown second operation rejects the whole batch byte-identically", async () => {
  resetFeedbackReceiptsForTest();
  const ws = workspace();
  try {
    const initial = JSON.parse(readFileSync(ws.filePath, "utf8"));
    const initialBytes = readFileSync(ws.filePath, "utf8");
    await assert.rejects(
      mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
        [add("c-1", "合法"), { type: "time-travel", comment: {} }], feedbackHash(initial), "req-e02"),
      (error: unknown) => error instanceof Error,
      "unknown operation type must fail the whole batch",
    );
    // 文件零变化：字节完全一致、comments 仍 0。
    assert.equal(readFileSync(ws.filePath, "utf8"), initialBytes, "file bytes untouched");
    const after = JSON.parse(readFileSync(ws.filePath, "utf8"));
    assert.equal(after.comments.length, 0);
    assert.equal(feedbackHash(after), feedbackHash(initial));
  } finally {
    ws.cleanup();
  }
});

test("TC-F2-24-03 E03 same-hash concurrent batches: exactly one wins, the other 409s with current hash", async () => {
  resetFeedbackReceiptsForTest();
  const ws = workspace();
  try {
    const initial = JSON.parse(readFileSync(ws.filePath, "utf8"));
    const baseHash = feedbackHash(initial);
    // 项目锁串行化两次提交：第一批成功后文档已变，第二批 CAS 失败。
    const first = mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
      [add("c-1", "第一批")], baseHash, "req-e03-a");
    const second = mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
      [add("c-2", "第二批")], baseHash, "req-e03-b");
    const outcomes = await Promise.allSettled([first, second]);
    const fulfilled = outcomes.filter((entry) => entry.status === "fulfilled");
    const rejected = outcomes.filter((entry) => entry.status === "rejected");
    assert.equal(fulfilled.length, 1, "exactly one batch wins");
    assert.equal(rejected.length, 1, "the other is rejected");
    const conflict = (rejected[0] as PromiseRejectedResult).reason as InstanceType<typeof DispatchError>
      & { currentHash?: string };
    assert.equal(conflict.code, "feedback_conflict", "loser gets feedback_conflict (HTTP 409 mapping)");
    assert.ok(typeof conflict.currentHash === "string" && conflict.currentHash.length === 64,
      "conflict carries the CURRENT hash");
    // 无交织部分结果：文档恰含胜者的 1 条。
    const document = JSON.parse(readFileSync(ws.filePath, "utf8"));
    assert.equal(document.comments.length, 1, "no interleaved partial result");
    assert.equal(document.comments[0].text, "第一批");
  } finally {
    ws.cleanup();
  }
});

test("TC-F2-24-04 E04 lost response retried with the same requestId replays the original receipt", async () => {
  resetFeedbackReceiptsForTest();
  const ws = workspace();
  try {
    const initial = JSON.parse(readFileSync(ws.filePath, "utf8"));
    const first = await mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
      [add("c-1", "只加一次")], feedbackHash(initial), "req-e04");
    // 响应丢失后同 requestId 重发：原回执、评论数不增。
    const replay = await mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
      [add("c-1", "只加一次")], feedbackHash(initial), "req-e04");
    assert.deepEqual(replay, first, "replay returns the original receipt");
    const document = JSON.parse(readFileSync(ws.filePath, "utf8"));
    assert.equal(document.comments.length, 1, "comment count did not grow");

    // 不同 requestId 的同一内容是真实第二次提交（按内容 add 查重拒绝）。
    await assert.rejects(
      mutateFeedback(generatedRoot, ws.projectsRoot, PROJECT_ID,
        [add("c-1", "只加一次")], feedbackHash(JSON.parse(readFileSync(ws.filePath, "utf8"))), "req-e04-b"),
      (error: unknown) => error instanceof DispatchError && error.code === "feedback_conflict",
    );
  } finally {
    ws.cleanup();
  }
});
