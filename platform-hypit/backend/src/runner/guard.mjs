// guard.mjs — C107-02 (task-107) local-tier child-process containment.
//
// Loaded via --import BEFORE tsx and the runner entry. The runner process runs
// under Node's permission model (scoped fs read/write), but tsx's esbuild
// service needs one child process. This guard narrows child_process to that
// single use: only executables inside the backend's own node_modules (or the
// node binary itself) may spawn. Author code attempting /bin/sh, curl or any
// host executable gets a hard error instead of an unrestricted subprocess.
//
// This is the local tier only; the deployment tier (compose.runner.yml:
// network_mode none, non-root, read-only rootfs, cap_drop ALL) is the hard
// boundary validated by C107-23.
import childProcess from "node:child_process";
import { realpathSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const backendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
// The engine build root arrives as an argv flag of the runner entry (never
// env, which author code could read).
function argvValue(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}
const distributionRoot = argvValue("--distribution-root")
  ? resolve(argvValue("--distribution-root"))
  : undefined;
const nativeAllowRoots = [
  resolve(backendRoot, "node_modules"),
  ...(distributionRoot === undefined ? [] : [resolve(distributionRoot, "node_modules")]),
];

function allowedExecutable(file) {
  if (typeof file !== "string") return false;
  if (file === process.execPath) return true;
  const resolved = resolve(file);
  return nativeAllowRoots.some((root) => resolved.startsWith(root + "/"));
}

function guard(name) {
  const original = childProcess[name];
  if (typeof original !== "function") return;
  childProcess[name] = function guardedSpawn(file, args, options) {
    if (!allowedExecutable(file)) {
      throw new Error(
        `runner child process denied: ${name} is restricted to engine tooling inside node_modules`,
      );
    }
    return original.call(this, file, args, options);
  };
}

for (const name of ["spawn", "spawnSync", "execFile", "execFileSync", "exec", "execSync"]) {
  guard(name);
}

// Native addons: sharp and friends inside the engine's node_modules are trusted
// tooling; an addon shipped inside a project slot is not.
const originalDlopen = process.dlopen;
if (typeof originalDlopen === "function") {
  process.dlopen = function guardedDlopen(module, filename, flags) {
    const resolved = typeof filename === "string" ? resolve(filename) : "";
    if (!nativeAllowRoots.some((root) => resolved.startsWith(root + "/"))) {
      throw new Error(`runner native addon denied: ${resolved} is outside engine tooling`);
    }
    return originalDlopen.call(this, module, filename, flags);
  };
}

// fork() only ever launches node itself; keep it available for tooling.
export const runnerGuardActive = true;
export { backendRoot, realpathSync };
