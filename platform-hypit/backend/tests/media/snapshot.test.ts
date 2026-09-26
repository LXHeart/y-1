// snapshot.test.ts — C107-11 (task-107) snapshot scheduling: exact frames and
// paginated grids come from the CURRENT document only (no new export Build),
// the schedule is deterministic, and out-of-range frames are refused.
import { strict as assert } from "node:assert";
import test from "node:test";

import { snapshotSchedule } from "../../src/tools/snapshot.ts";
import { DispatchError } from "../../src/commands/dispatcher.ts";

const document = { frameCount: 120 } as never;

test("exact frames are honored and grid pages paginate deterministically", () => {
  const schedule = snapshotSchedule({ document, frames: [0, 59, 119], framesPerPage: 2 });
  assert.deepEqual(schedule.frames, [0, 59, 119]);
  assert.equal(schedule.pages.length, 2);
  assert.deepEqual(schedule.pages[0]!.frameIndices, [0, 59]);
  assert.deepEqual(schedule.pages[1]!.frameIndices, [119]);
});

test("default schedule samples first/middle/last, preserving exact frame labels", () => {
  const schedule = snapshotSchedule({ document });
  assert.deepEqual(schedule.frames, [0, 59, 119]);
  // single page when no pagination is requested
  assert.equal(schedule.pages.length, 1);
});

test("out-of-range or fractional frames are refused", () => {
  assert.throws(() => snapshotSchedule({ document, frames: [120] }), DispatchError);
  assert.throws(() => snapshotSchedule({ document, frames: [-1] }), DispatchError);
  assert.throws(() => snapshotSchedule({ document, frames: [10.5] }), DispatchError);
});
