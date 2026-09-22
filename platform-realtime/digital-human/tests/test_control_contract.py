"""Task 105C C105C-05: Java↔runtime control contract—command idempotency and state-callback ordering.

契约要点（K07.2/K07.3、K13.1）：
- 每条控制命令带 commandId+payloadHash；HTTP 超时只按原键查询/重放，不创造第二次执行；
- runtime 状态回调（事件）seq 由 Java 分配；runtime 不能凭迟到/乱序回调推进权威状态；
- 重复 commandId 返回有限 receipt（同结果），不二次创建会话。
"""

from __future__ import annotations

import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))


class CommandConflict(RuntimeError):
    """同 commandId 但 payloadHash 不同（协议违例）。"""


@dataclass
class CommandReceipt:
    command_id: str
    payload_hash: str
    session_id: str
    result: str


@dataclass
class CommandBroker:
    """Java 侧命令幂等语义的最小模型：同 commandId+hash → 同 receipt；异 hash → 冲突。"""

    receipts: Dict[str, CommandReceipt] = field(default_factory=dict)
    executions: List[str] = field(default_factory=list)

    def execute(self, session_id: str, command_id: str, payload_hash: str, result: str) -> CommandReceipt:
        existing = self.receipts.get(command_id)
        if existing is not None:
            if existing.payload_hash != payload_hash:
                raise CommandConflict(f"command {command_id} replayed with different payload")
            return existing  # 重复命令：回原 receipt，不二次执行
        receipt = CommandReceipt(command_id, payload_hash, session_id, result)
        self.receipts[command_id] = receipt
        self.executions.append(command_id)
        return receipt


@dataclass
class EventSink:
    """Java 分配 seq 的最小模型：迟到/乱序回调只丢弃，不推进权威状态。"""

    last_seq: int = 0
    accepted: List[int] = field(default_factory=list)

    def publish(self, seq: int) -> bool:
        if seq != self.last_seq + 1:
            return False  # 乱序/迟到：丢弃（调用方拉 snapshot 补齐，不重放生成）
        self.last_seq = seq
        self.accepted.append(seq)
        return True


def test_duplicate_command_replays_receipt_without_second_execution():
    broker = CommandBroker()
    first = broker.execute("s-1", "cmd-1", "hash-a", "accepted")
    # HTTP 超时后同键重试（同 hash）：回原 receipt。
    replay = broker.execute("s-1", "cmd-1", "hash-a", "accepted")
    assert replay is first
    assert broker.executions == ["cmd-1"]  # 只执行一次

    # 同 commandId 异 payloadHash → 协议冲突（不静默顶替）。
    with pytest.raises(CommandConflict):
        broker.execute("s-1", "cmd-1", "hash-b", "accepted")


def test_second_session_never_created_on_timeout_retry():
    broker = CommandBroker()
    sessions: List[str] = []

    def create_session(session_id: str, command_id: str) -> str:
        receipt = broker.execute(session_id, command_id, "hash", "accepted")
        if receipt.command_id == command_id and receipt.session_id not in sessions:
            sessions.append(receipt.session_id)
        return receipt.session_id

    create_session("s-2", "cmd-2")
    create_session("s-2", "cmd-2")  # 超时重试同键
    assert sessions == ["s-2"]  # 未开第二 runner


def test_out_of_order_state_callbacks_are_dropped_not_applied():
    sink = EventSink()
    assert sink.publish(1) is True
    assert sink.publish(2) is True
    # 迟到（旧 seq）与跳号：丢弃，权威状态不被旧回调推进。
    assert sink.publish(1) is False
    assert sink.publish(4) is False
    assert sink.last_seq == 2
    assert sink.accepted == [1, 2]
    # 缺口按序补齐后恢复。
    assert sink.publish(3) is True


def test_concurrent_duplicate_commands_execute_once():
    broker = CommandBroker()
    barrier = threading.Barrier(4)
    results: List[CommandReceipt] = []
    lock = threading.Lock()

    def worker() -> None:
        barrier.wait()
        receipt = broker.execute("s-3", "cmd-3", "hash", "accepted")
        with lock:
            results.append(receipt)

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert broker.executions == ["cmd-3"]
    assert len({receipt.command_id for receipt in results}) == 1
