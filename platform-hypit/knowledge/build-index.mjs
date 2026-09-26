// build-index.mjs — 任务书 #107-2 C107-14 (14.1)：构建 Hypit 知识索引。
//
// 从冻结的 upstream skills 源（.generated/hypit/skills/hypit）枚举 SKILL.md +
// references/**/*.md（共 65 份），逐份记录 path/sha256/title/topic/sourceCommit，
// 校验相对内部链接闭合，然后：
//   1. 写 platform-hypit/knowledge/index.json（引擎侧读取的同一份索引）
//   2. 复制原文档 + 索引到 JR（intelligence-service 资源目录 hypit/knowledge/）
// 内容必须与 U 一致：本脚本只复制与登记，绝不改写正文或追加提示。
//
// 用法：node platform-hypit/knowledge/build-index.mjs
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync, cpSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(fileURLToPath(import.meta.url), "../../..");
const sourceRoot = join(repoRoot, "platform-hypit/.generated/hypit/skills/hypit");
const brokerIndex = join(repoRoot, "platform-hypit/knowledge/index.json");
const javaResources = join(
  repoRoot,
  "platform-java/services/intelligence-service/src/main/resources/hypit/knowledge",
);
const sourceCommit = JSON.parse(
  readFileSync(join(repoRoot, "platform-hypit/patches/manifest.json"), "utf8"),
).upstreamCommit;

function listMarkdown(dir, prefix = "") {
  const found = [];
  for (const name of readdirSync(dir).sort()) {
    const absolute = join(dir, name);
    const relative = prefix ? `${prefix}/${name}` : name;
    if (statSync(absolute).isDirectory()) found.push(...listMarkdown(absolute, relative));
    else if (name.endsWith(".md")) found.push(relative);
  }
  return found;
}

function titleOf(text, fallback) {
  const match = /^#\s+(.+)$/mu.exec(text);
  return match === null ? fallback : match[1].trim();
}

const files = ["SKILL.md", ...listMarkdown(join(sourceRoot, "references"), "references")];
const documents = [];
for (const relative of files) {
  const bytes = readFileSync(join(sourceRoot, relative));
  const text = bytes.toString("utf8");
  const topic = relative.includes("/") ? relative.split("/")[1] : "root";
  for (const match of text.matchAll(/\]\(([^)#]+?\.md)\)/gu)) {
    const link = match[1];
    if (/^https?:\/\//u.test(link)) continue;
    const target = relative.includes("/")
      ? join(sourceRoot, dirname(relative), link)
      : join(sourceRoot, link);
    if (!existsSync(target)) {
      throw new Error(`broken internal link in ${relative}: ${link}`);
    }
  }
  documents.push({
    path: relative,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    title: titleOf(text, relative),
    topic,
    sourceCommit,
  });
}

const index = {
  format: "y1.hypit-knowledge@1",
  sourceRoot: "platform-hypit/.generated/hypit/skills/hypit",
  sourceCommit,
  documents,
};
writeFileSync(brokerIndex, `${JSON.stringify(index, null, 1)}\n`);

cpSync(sourceRoot, javaResources, { recursive: true });
writeFileSync(join(javaResources, "index.json"), `${JSON.stringify(index, null, 1)}\n`);
console.log(`knowledge index: ${documents.length} docs -> ${brokerIndex} + ${javaResources}`);
