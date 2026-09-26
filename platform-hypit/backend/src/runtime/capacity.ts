// capacity.ts — C107-09 (task-107) broker-level local render capacity (D-05).
//
// The deployment default allows exactly ONE heavy local render at a time;
// this is a broker admission gate on top of the native per-endpoint weighted
// claims, NOT a replacement. A build holds its slot from admission until the
// merged observation says only remote waits remain (a build parked on remote
// polls must not lock every other build out of local rendering — 09.5).
// The gate is FIFO per acquire() order and keyed by engineBuildId so a
// crashed watcher can release on the next observation.
export type CapacityLease = {
  readonly key: string;
  readonly position: number;
};

type Waiter = {
  readonly key: string;
  readonly resolve: (lease: CapacityLease) => void;
};

export class RenderCapacity {
  private readonly maxLocal: number;
  private readonly held = new Set<string>();
  private readonly queue: Waiter[] = [];

  constructor(maxLocalRenders: number) {
    if (!Number.isSafeInteger(maxLocalRenders) || maxLocalRenders < 1) {
      throw new Error("maxLocalRenders must be a positive integer");
    }
    this.maxLocal = maxLocalRenders;
  }

  /** FIFO admission; resolves immediately when a slot is free. */
  async acquire(key: string): Promise<CapacityLease> {
    if (this.held.has(key)) {
      return { key, position: 0 };
    }
    if (this.held.size < this.maxLocal) {
      this.held.add(key);
      return { key, position: 0 };
    }
    const lease = new Promise<CapacityLease>((resolve) => {
      this.queue.push({ key, resolve });
    });
    return await lease;
  }

  /** Release a slot (idempotent); FIFO hands it to the next waiter. */
  release(key: string): void {
    if (!this.held.has(key)) return;
    this.held.delete(key);
    while (this.held.size < this.maxLocal) {
      const next = this.queue.shift();
      if (next === undefined) return;
      if (this.held.has(next.key)) {
        // Already re-acquired through the fast path — keep draining.
        next.resolve({ key: next.key, position: 0 });
        continue;
      }
      this.held.add(next.key);
      next.resolve({ key: next.key, position: this.held.size - 1 });
    }
  }

  /** Drop every waiter for a key whose owner no longer wants admission. */
  abandon(key: string): void {
    for (let index = this.queue.length - 1; index >= 0; index -= 1) {
      const waiter = this.queue[index];
      if (waiter !== undefined && waiter.key === key) {
        this.queue.splice(index, 1);
      }
    }
    this.release(key);
  }

  isHeld(key: string): boolean {
    return this.held.has(key);
  }

  describe(): { maxLocalRenders: number; active: readonly string[]; queued: readonly string[] } {
    return {
      maxLocalRenders: this.maxLocal,
      active: [...this.held],
      queued: this.queue.map((waiter) => waiter.key),
    };
  }
}
