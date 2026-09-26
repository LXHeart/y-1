// proxy.test.ts — C107-12 (task-107): only same-session base-path prefixes
// proxy; cross-origin handshakes and path escapes are refused.
import { strict as assert } from "node:assert";
import test from "node:test";

import { planProxy } from "../../src/studio/proxy.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

test("same-session paths map to upstream routes; escapes are refused", () => {
  const decision = planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/__studio/session",
    host: "app.example",
  });
  assert.equal(decision.targetPath, "/__studio/session");
  assert.equal(decision.upgrade, false);
  assert.equal(planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/", host: "app.example",
  }).targetPath, "/");
  assert.throws(() => planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-2/__studio/session",
    host: "app.example",
  }), DispatchError, "another session's prefix is refused");
  assert.throws(() => planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/../../etc", host: "app.example",
  }), DispatchError);
});

test("websocket upgrades need the ws protocol and same-origin", () => {
  const ok = planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/__studio/ws",
    upgrade: "websocket", host: "app.example",
  });
  assert.equal(ok.upgrade, true);
  assert.throws(() => planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/__studio/ws",
    upgrade: "h2c", host: "app.example",
  }), DispatchError);
  assert.throws(() => planProxy({
    basePath: "/studio", sessionId: "st-1", method: "GET", path: "/studio/st-1/__studio/ws",
    upgrade: "websocket", origin: "https://evil.example", host: "app.example",
  }), DispatchError, "cross-origin WS handshake refused");
});
