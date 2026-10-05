#!/usr/bin/env node
// Recursive test runner for platform-hypit/backend (task-107 K13.2).
// Collects tests/<group>/**/*.test.ts, runs them through Node's built-in test
// runner with tsx loaded for TypeScript. TEST_GROUP restricts execution to one
// allowed top-level group; an unknown group or an empty match set is an error.
import { readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const backendRoot = fileURLToPath(new URL("..", import.meta.url));
const testsRoot = join(backendRoot, "tests");
const allowedGroups = ["engine", "workspace", "media", "providers", "runtime", "studio", "agent-integration", "image-isolation", "runner-isolation"];

const isolated = { "image-isolation": "fix2-c03.test.ts", "runner-isolation": "fix2-c04.test.ts" };
const requested = process.env.TEST_GROUP;
let selected = [];
if (requested === undefined) {
  selected = collect(testsRoot);
} else {
  if (!allowedGroups.includes(requested)) {
    console.error(`TEST_GROUP must be one of: ${allowedGroups.join(", ")} (got ${requested})`);
    process.exit(2);
  }
  selected = requested in isolated
    ? [join(testsRoot, "agent-integration", isolated[requested])]
    : collect(join(testsRoot, requested));
}
if (requested === undefined || requested === "agent-integration") {
  selected = selected.filter(file => !Object.values(isolated).some(name => file.endsWith("/" + name)));
  console.log("HOST_NATIVE only; required Docker gates run separately via verify-107-fix-2.sh --stage card --card C107F2-03 / C107F2-04.");
}
selected.sort();

if (selected.length === 0) {
  console.error(requested === undefined
    ? `no *.test.ts files found under ${relative(backendRoot, testsRoot)}`
    : `no *.test.ts files found under tests/${requested}`);
  process.exit(1);
}

console.log(`running ${selected.length} test file(s)${requested === undefined ? "" : ` in group ${requested}`}`);
const result = spawnSync(process.execPath, [
  "--import", "tsx",
  "--test",
  // C107F2-20 实录：文件并发时同组测试共享端口带（studio 25179-25478），TCP 探测
  // 的空口判定跨进程会竞态——两个 vite 子进程同口相撞，败者早亡被清成 404。
  // 本仓约定重型任务默认单 worker，这里一并钉死串行文件执行。
  "--test-concurrency=1",
  requested in isolated ? "--test-timeout=2400000" : "--test-timeout=600000",
  ...selected.map((file) => relative(backendRoot, file)),
], { cwd: backendRoot, stdio: "inherit" });
process.exit(result.status ?? 1);

function collect(dir) {
  const found = [];
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return found;
  }
  for (const name of entries) {
    const abs = join(dir, name);
    const st = statSync(abs);
    if (st.isDirectory()) found.push(...collect(abs));
    else if (name.endsWith(".test.ts")) found.push(abs);
  }
  return found;
}
