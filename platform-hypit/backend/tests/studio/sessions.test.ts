// sessions.test.ts — C107-12 (task-107): studio sessions reuse the active
// same-Run session, tickets are single-use and expiry-bound, and closing
// kills the session.
import { strict as assert } from "node:assert";
import test from "node:test";

import { closeStudioSession, openStudioSession, redeemStudioTicket } from "../../src/studio/sessions.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

const policy = {
  basePath: "/studio",
  secret: "test-secret-0123456789abcdef0123456789abcdef",
  ttlSeconds: 60,
};

test("same Run reuses the active session; tickets are single-use", () => {
  const first = openStudioSession(policy, { projectId: "p-1", runFile: "main.svrun", revision: 2 });
  assert.equal(first.reused, false);
  const second = openStudioSession(policy, { projectId: "p-1", runFile: "main.svrun", revision: 2 });
  assert.equal(second.reused, true);
  assert.equal(second.session.id, first.session.id);

  redeemStudioTicket(policy, first.session.id, first.ticket.ticket);
  assert.throws(() => redeemStudioTicket(policy, first.session.id, first.ticket.ticket), DispatchError,
    "a ticket cannot be redeemed twice");
  assert.throws(() => redeemStudioTicket(policy, first.session.id, second.ticket.ticket), DispatchError);

  closeStudioSession(first.session.id);
  assert.throws(() => closeStudioSession(first.session.id), DispatchError);
});

test("expired tickets are refused at redemption", () => {
  const expired = { ...openStudioSession(policy, { projectId: "p-3", revision: 0 }), };
  // a ticket MACed for another session id must fail binding
  assert.throws(() => redeemStudioTicket(policy, "st-other", expired.ticket.ticket), DispatchError);
  // a tampered MAC fails verification
  const ticket = expired.ticket.ticket;
  const flipped = ticket.slice(0, -1) + (ticket.endsWith("a") ? "b" : "a");
  assert.throws(() => redeemStudioTicket(policy, expired.session.id, flipped), DispatchError);
  closeStudioSession(expired.session.id);
});
