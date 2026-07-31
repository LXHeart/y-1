-- 草场 identity V12：通知消费者的 inbox（幂等去重）。Slice 12 Stage 2。
--
-- 镜像 marketplace V7 的 marketplace_inbox：identity 现在也要消费自己的 outbox topic
-- （grassland.identity.events）来派生站内通知，故需要消费者侧的幂等表。
--
-- 两道幂等闸门：
--  1) 本表（consumer_name, event_id）：同一消费者不重复处理同一事件——Kafka at-least-once 重投、
--     重启重放都靠它吸收。同 ID 异内容（payload SHA-256 不符）判为契约冲突 → 抛错进 DLT，不静默覆盖。
--  2) notification 表的 UNIQUE(source_event_id, account_id)：换消费者名 / 手工补投 / 同事件多收件人
--     都不会产生重复收件。
--
-- (consumer_name, source_topic, source_partition, source_offset) 唯一：防止「同一 offset 被当成两个事件」
-- 的错位——Kafka 重分配后 offset 复用时能发现冲突。

CREATE TABLE identity_inbox (
    consumer_name varchar(128) NOT NULL,
    event_id text NOT NULL,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload_sha256 char(64) NOT NULL,
    source_topic text NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id),
    UNIQUE (consumer_name, source_topic, source_partition, source_offset)
);
