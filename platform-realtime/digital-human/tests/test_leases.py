"""Task 105C C105C-02 / TC105C-02 (Python side): Java disconnected -> stop by itself on expiry; old epoch cannot be renewed."""

from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path
import sys

import pytest

# uv 不安装项目自身（pyproject [tool.uv] package=false）：与既有测试同样显式引入 src/。
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from grassland_dh.leases import LeaseStale, LeaseManager, RuntimeLease  # noqa: E402


def test_lease_expires_and_runtime_stops_without_java():
    now = datetime(2026, 9, 23, 0, 0, 0)
    manager = LeaseManager()
    stopped_peer = []
    manager.install(
        RuntimeLease("s-1", 1, now + timedelta(seconds=30)),
        on_stop=lambda session_id: stopped_peer.append(session_id),
    )
    # During the validity period: normal heartbeat, inference continues.
    manager.tick(now + timedelta(seconds=10))
    assert manager.stopped is False
    assert stopped_peer == []

    # Java disconnected (no renew) -> stop by itself on expiry (inference/peer stop).
    manager.tick(now + timedelta(seconds=31))
    assert manager.stopped is True
    assert stopped_peer == ["s-1"]
    assert manager.stopped_reasons == ["lease_expired:s-1"]
    # Stop is one-way: repeated ticks do not produce duplicate stop records.
    manager.tick(now + timedelta(seconds=35))
    assert manager.stopped_reasons == ["lease_expired:s-1"]


def test_old_epoch_cannot_renew():
    now = datetime(2026, 9, 23, 0, 0, 0)
    manager = LeaseManager()
    manager.install(RuntimeLease("s-2", 1, now + timedelta(seconds=30)), on_stop=lambda _sid: None)
    # Renewal with old epoch (after takeover the epoch has rotated) -> LeaseStale, lease not extended.
    with pytest.raises(LeaseStale):
        manager.renew_from_java(now + timedelta(seconds=5), lease_epoch=0)
    assert manager.lease.lease_epoch == 1


def test_expired_lease_cannot_be_revived_by_renewal():
    now = datetime(2026, 9, 23, 0, 0, 0)
    lease = RuntimeLease("s-3", 7, now + timedelta(seconds=30))
    # Renewal after expiry (even if the epoch matches) -> rejected: no revival.
    with pytest.raises(LeaseStale):
        lease.renew(now + timedelta(seconds=31), lease_epoch=7)


def test_current_epoch_renewal_extends_ttl():
    now = datetime(2026, 9, 23, 0, 0, 0)
    lease = RuntimeLease("s-4", 3, now + timedelta(seconds=30))
    renewed = lease.renew(now + timedelta(seconds=20), lease_epoch=3)
    assert renewed.lease_epoch == 3
    assert renewed.lease_expires_at == now + timedelta(seconds=50)
    assert renewed.expired(now + timedelta(seconds=49)) is False
