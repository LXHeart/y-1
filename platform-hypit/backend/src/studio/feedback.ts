// feedback.ts — C107-18 (task-107) broker bridge over the upstream FEEDBACK store.
//
// K10.2/K11.5: FEEDBACK.json inside the project workspace is the ONLY editable
// comment truth. This bridge adds no second store: it loads the upstream
// studio modules VERBATIM from the pinned distribution checkout
// (createFeedbackStore / readFeedbackMutation keep their exact add/replace/
// delete semantics, stable ids, run binding and second times) and layers the
// broker-side baseHash CAS on top — a stale hash rejects only the current
// mutation and never overwrites concurrent edits (TC107-18-02/03).
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { DispatchError } from "../commands/dispatcher.ts";
import { projectRootFor } from "../workspace/provision.ts";

export type FeedbackComment = {
  readonly id: string;
  readonly run: string;
  readonly at: number;
  readonly text: string;
  readonly resolved?: boolean;
};

export type FeedbackDocument = { readonly comments: readonly FeedbackComment[] };

type UpstreamStore = {
  readonly path: string;
  read(): Promise<FeedbackDocument>;
  mutate(mutation: unknown): Promise<FeedbackDocument>;
};

/**
 * The upstream distribution checkout carries the studio package's TS sources.
 * A single absolute file URL keeps the load pinned to the SAME tree the engine
 * runs (the package-resolution hook only resolves literal export keys, and the
 * studio package publishes `./src/*` as a wildcard).
 */
function upstreamModule(distributionRoot: string, relative: string): Promise<Record<string, unknown>> {
  const file = join(distributionRoot, "packages/studio/src", relative);
  if (!existsSync(file)) {
    throw new DispatchError("engine_unavailable", `distribution lacks studio module ${relative}`);
  }
  return import(pathToFileURL(file).href) as Promise<Record<string, unknown>>;
}

export function feedbackHash(document: FeedbackDocument): string {
  return createHash("sha256").update(JSON.stringify(document)).digest("hex");
}

async function upstreamStore(distributionRoot: string, workspaceRoot: string): Promise<UpstreamStore> {
  const module = await upstreamModule(distributionRoot, "feedback-store.ts");
  const createFeedbackStore = module.createFeedbackStore as (root: string) => UpstreamStore;
  if (typeof createFeedbackStore !== "function") {
    throw new DispatchError("engine_unavailable", "distribution feedback-store has no createFeedbackStore");
  }
  return createFeedbackStore(workspaceRoot);
}

function workspaceRootFor(projectsRoot: string, projectId: string): string {
  return join(projectRootFor(projectsRoot, projectId), "work");
}

/** The upstream FeedbackView shape: file, run, filtered comments. */
export async function readFeedback(
  distributionRoot: string,
  projectsRoot: string,
  projectId: string,
  run: string | undefined,
): Promise<unknown> {
  const workspaceRoot = workspaceRootFor(projectsRoot, projectId);
  const store = await upstreamStore(distributionRoot, workspaceRoot);
  const document = await store.read();
  const comments = run === undefined ? document.comments : document.comments.filter((entry) => entry.run === run);
  return {
    file: "FEEDBACK.json",
    ...(run === undefined ? {} : { run }),
    comments,
    hash: feedbackHash(document),
  };
}

/**
 * Apply a batch of upstream-format mutations ATOMICALLY (C107F2-24 / §6.11).
 *
 * 先全量解析/校验（readFeedbackMutation 上游 schema），再在统一项目锁内读最新文档
 * 比较 expectedHash（CAS 到落盘之间无窗口），在内存预计算最终文档（逐条沿用上游
 * store 的冲突检查语义），全部成功后同目录临时文件 + rename 一次提交——中途失败
 * 字节零变化。requestId 幂等：同键重放返回原回执，评论数不增。
 */
type FeedbackMutationParsed = {
  readonly type: "add" | "replace" | "delete";
  readonly comment?: FeedbackComment;
  readonly before?: FeedbackComment;
};

/** requestId → 回执（进程内幂等键；重放返回原回执，不重复应用）。 */
const feedbackReceipts = new Map<string, unknown>();
const FEEDBACK_RECEIPT_LIMIT = 500;

export async function mutateFeedback(
  distributionRoot: string,
  projectsRoot: string,
  projectId: string,
  mutations: readonly unknown[],
  expectedHash: string | undefined,
  requestId?: string,
): Promise<unknown> {
  if (!Array.isArray(mutations) || mutations.length === 0) {
    throw new DispatchError("invalid_input", "feedback.mutate needs a non-empty mutations array");
  }
  const receiptKey = requestId === undefined || requestId.length === 0 ? null : `${projectId}:${requestId}`;
  if (receiptKey !== null && feedbackReceipts.has(receiptKey)) {
    return feedbackReceipts.get(receiptKey);
  }

  const workspaceRoot = workspaceRootFor(projectsRoot, projectId);
  const feedbackModule = await upstreamModule(distributionRoot, "feedback.ts");
  const readFeedbackMutation = feedbackModule.readFeedbackMutation as (value: unknown) => FeedbackMutationParsed;
  const readFeedbackDocumentModule = feedbackModule.readFeedbackDocument as (value: unknown) => FeedbackDocument;
  if (typeof readFeedbackMutation !== "function" || typeof readFeedbackDocumentModule !== "function") {
    throw new DispatchError("engine_unavailable", "distribution feedback module lacks mutation/document readers");
  }
  // 步骤 1a：先解析/验证全部 mutation——任何一条非法整批拒绝（文件零接触）。
  const parsed: FeedbackMutationParsed[] = [];
  for (const raw of mutations) {
    parsed.push(readFeedbackMutation(raw));
  }

  const store = await upstreamStore(distributionRoot, workspaceRoot);
  // 步骤 1b/3：统一项目锁内 CAS → 内存应用 → 一次原子提交（CAS 检查到落盘之间
  // 不可能插入其他请求——revision 写与 Feedback 写共用 withProjectLock 边界）。
  const { withProjectLock } = await import("../workspace/transactions.ts");
  const receipt = await withProjectLock(join(workspaceRoot, ".."), async () => {
    const before = await store.read();
    const baseHash = feedbackHash(before);
    if (expectedHash !== undefined && expectedHash !== baseHash) {
      const error = new DispatchError("feedback_conflict",
        "FEEDBACK changed outside this view; input preserved");
      // 冲突回执携带当前 hash（§5 RULE-11：409 与当前 hash 一起给）。
      (error as DispatchError & { currentHash?: string }).currentHash = baseHash;
      throw error;
    }
    // 步骤 1c：内存预计算最终文档（逐条镜像上游 feedback-store apply 的冲突检查——
    // add 查重、replace/delete 验 before 深相等与 id/run 保留），不逐条写原文件。
    let comments = [...before.comments];
    for (const mutation of parsed) {
      if (mutation.type === "add") {
        if (!mutation.comment) throw new DispatchError("invalid_input", "add mutation lacks comment");
        if (comments.some((comment) => comment.id === mutation.comment!.id)) {
          throw new DispatchError("feedback_conflict", "This comment already exists.");
        }
        comments = [...comments, mutation.comment];
        continue;
      }
      if (!mutation.before) throw new DispatchError("invalid_input", `${mutation.type} mutation lacks before`);
      const index = comments.findIndex((comment) => comment.id === mutation.before!.id);
      const current = index < 0 ? undefined : comments[index];
      if (current === undefined || JSON.stringify(current) !== JSON.stringify(mutation.before)) {
        throw new DispatchError("feedback_conflict",
          "This comment changed outside this view. Your unsaved text is kept in the editor; reopen the comment to edit its latest version.");
      }
      if (mutation.type === "delete") {
        comments = comments.filter((_, position) => position !== index);
      } else {
        if (!mutation.comment || mutation.comment.id !== mutation.before.id
          || mutation.comment.run !== mutation.before.run) {
          throw new DispatchError("invalid_input", "Editing a comment preserves its id and Run.");
        }
        comments = comments.map((comment, position) => (position === index ? mutation.comment! : comment));
      }
    }
    const finalDocument: FeedbackDocument = { comments };
    // 步骤 2：所有 operation 成功后一次提交（store.read 后文档未变——锁内），经上游
    // readFeedbackDocument 复核 schema/排序/标识后写同目录临时文件 + rename。
    const verified = readFeedbackDocumentModule(JSON.parse(JSON.stringify(finalDocument)));
    const serialized = `${JSON.stringify(verified, null, 2)}\n`;
    const { writeFile, rename, unlink } = await import("node:fs/promises");
    const temporary = join(workspaceRoot, `.FEEDBACK.c24.${Date.now()}.tmp`);
    try {
      await writeFile(temporary, serialized, { encoding: "utf8", flag: "wx" });
      await rename(temporary, store.path);
    } finally {
      await unlink(temporary).catch(() => undefined);
    }
    return { comments: finalDocument.comments, hash: feedbackHash(finalDocument), applied: parsed.length };
  });

  if (receiptKey !== null) {
    feedbackReceipts.set(receiptKey, receipt);
    if (feedbackReceipts.size > FEEDBACK_RECEIPT_LIMIT) {
      const oldest = feedbackReceipts.keys().next().value;
      if (oldest !== undefined) feedbackReceipts.delete(oldest);
    }
  }
  return receipt;
}

/** 测试钩子：清空幂等回执（进程级状态不得跨用例泄漏）。 */
export function resetFeedbackReceiptsForTest(): void {
  feedbackReceipts.clear();
}
