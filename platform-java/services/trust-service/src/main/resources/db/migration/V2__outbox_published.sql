-- 草场 trust-service V2（Epic 6 Slice 6B：outbox Kafka 发布器）。
-- trust_outbox 加 published_at（null=未发布）；OutboxPublisher 轮询未发布行发 Kafka 后置位。
ALTER TABLE trust_outbox ADD COLUMN published_at timestamptz;
