"""Runtime lease (Task 105C C105C-02 / K04): the runtime stops by itself on expiry.

The Python side holds a short-lived execution lease issued by Java (leaseEpoch +
leaseExpiresAt). The lease is renewed only with the **current** epoch; an old epoch
renewal is rejected (stale), and once the lease expires the runtime must stop
inference/peer by itself — Java being unreachable must not keep generation running.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Callable, List


class LeaseStale(RuntimeError):
    """Renewal with an epoch that is no longer current."""


@dataclass(frozen=True)
class RuntimeLease:
    """Immutable lease state; renew() returns a NEW lease (no in-place mutation)."""

    session_id: str
    lease_epoch: int
    lease_expires_at: datetime
    _stop_callbacks: List[Callable[[str], None]] = field(default_factory=list)

    def expired(self, now: datetime) -> bool:
        return now >= self.lease_expires_at

    def renew(self, now: datetime, lease_epoch: int, ttl: timedelta = timedelta(seconds=30)) -> "RuntimeLease":
        """Only the current epoch may renew (old epochs -> LeaseStale, no extension)."""
        if lease_epoch != self.lease_epoch:
            raise LeaseStale(
                f"stale lease epoch {lease_epoch} != current {self.lease_epoch} (session {self.session_id})"
            )
        if self.expired(now):
            # Renewal after expiry is no longer allowed: the lease has expired, no revival (K04).
            raise LeaseStale(f"lease for session {self.session_id} already expired; cannot renew")
        return RuntimeLease(
            session_id=self.session_id,
            lease_epoch=self.lease_epoch,
            lease_expires_at=now + ttl,
            _stop_callbacks=list(self._stop_callbacks),
        )

    def ensure_active(self, now: datetime) -> None:
        """On expiry, immediately trigger stop callbacks (stop inference/peer on their own).

        Once triggered, repeated calls are idempotent (stop is one-way).
        """
        if self.expired(now):
            for callback in self._stop_callbacks:
                callback(self.session_id)


@dataclass
class LeaseManager:
    """The runtime side holds the current lease: Java disconnection -> stop by itself after expiry."""

    lease: RuntimeLease | None = None
    stopped: bool = False
    stopped_reasons: list[str] = field(default_factory=list)

    def install(self, lease: RuntimeLease, on_stop: Callable[[str], None]) -> None:
        self.lease = lease
        self.stopped = False
        self.stopped_reasons = []
        # ensure_active internally shares a mutable stopped list: record via closure.
        holder = self

        def record(session_id: str) -> None:
            holder.stopped = True
            holder.stopped_reasons.append(f"lease_expired:{session_id}")

        self.lease = RuntimeLease(
            session_id=lease.session_id,
            lease_epoch=lease.lease_epoch,
            lease_expires_at=lease.lease_expires_at,
            _stop_callbacks=[record, on_stop],
        )

    def tick(self, now: datetime) -> None:
        """Periodic check: on expiry, stop by itself (even if Java is unreachable); stop is one-way (no re-triggering)."""
        if self.lease is not None and not self.stopped:
            self.lease.ensure_active(now)

    def renew_from_java(self, now: datetime, lease_epoch: int) -> None:
        """Java renewal fails as usual if the epoch is old or already expired."""
        assert self.lease is not None, "lease not installed"
        self.lease = self.lease.renew(now, lease_epoch)
