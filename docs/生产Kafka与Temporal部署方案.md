# 生产 Kafka 与 Temporal 部署方案

## 决策

生产环境不使用仓库内的单节点 Kafka 和 Temporal `start-dev + SQLite`。应用通过
`docker-compose.production.yml` 连接外部托管或独立部署的高可用集群；本仓库只拥有客户端配置、
Topic/Namespace 契约、发布门禁与故障演练，不在应用 Compose 内维护有状态集群。

## Kafka 基线

- 至少 3 broker、跨 3 个故障域，replication factor 3，`min.insync.replicas=2`。
- 客户端使用 `SASL_SSL + SCRAM-SHA-512`，每个环境独立账号；禁止公网匿名与 PLAINTEXT。
- 生产者保持 `acks=all`、idempotence、有限重试；领域写入继续通过 Outbox，消费者继续通过 Inbox/幂等键收敛重复。
- Topic：`grassland.identity.events`、`grassland.marketplace.events`、`grassland.finance.events`、
  `grassland.trust.events`、`grassland.intelligence.events` 及各自 DLT。按事件保留与审计要求设置 retention，
  DLT 不使用短于运营处置 SLA 的保留期。
- 上线门禁：验证 broker 跨故障域、ACL、证书链、consumer lag、outbox age、DLT 告警和 receiver 送达。

## Temporal 基线

- 使用托管 Temporal 或独立多副本集群，持久层多可用区；禁止 `start-dev` 与 SQLite。
- 独立 namespace `grassland-production`，应用以 mTLS 连接，证书私钥只读挂载。
- Task Queue 保持 `marketplace-saga` 与 `trust-adjudication`；每个队列至少 2 个 worker 副本，按 backlog 扩容。
- Namespace retention 必须覆盖最长业务窗口、客服等待期与运维恢复时间；变更 Workflow 代码前执行 replay 测试。
- 上线门禁：验证 namespace、mTLS、worker poller、schedule-to-start latency、stuck workflow 告警、
  workflow history 归档和持久层恢复演练。

## 发布与回退

`scripts/production-release.sh` 固定加载 `docker-compose.production.yml`。overlay 会移除应用对本地
Kafka/Temporal 的依赖，并把客户端证书/Truststore 只读挂载。生产密钥校验会拒绝本地地址、默认 Temporal
namespace、PLAINTEXT Kafka、缺失 mTLS 文件和权限过宽的 Temporal 私钥。

应用回滚不能回滚已执行的 Workflow history 或 Kafka 事件。Workflow 变更必须保持 replay 兼容；事件 schema
采用向后兼容扩展。若新版本无法消费旧历史/事件，停止发布，不得靠清空 Topic、重置 offset 或终止 Workflow 规避。

## 首次上线验收

1. 在隔离生产候选环境创建 Topic、ACL、Namespace 和客户端证书。
2. 执行生产 preflight 与 Compose 展开检查，确认 `kafka`、`temporal` 本地服务不在应用启动依赖中。
3. 各制造一笔任务预留、确认结算、争议审判，观察 Outbox 到 Kafka、消费者 Inbox 和 Temporal history。
4. 分别停止一个 broker、一个 worker 副本和一个持久层只读副本，确认业务无数据丢失且告警送达。
5. 演练证书轮换、consumer group 回滚、Workflow worker 回滚，并记录实测 RPO/RTO。

## Trace 验收

本地 collector 只用于开发链路 smoke，不进入生产 overlay。运行：

```bash
./scripts/local-otel-trace-smoke.sh
```

该命令启动隔离的 `observability` Compose project，向 OTLP/HTTP receiver 发送一条唯一 trace，
并从 collector debug exporter 日志确认 trace/span ID 已被接收，完成后自动清理。它证明本地
receiver 和 HTTP delivery 路径可用，不证明生产外部 collector、Kafka/Temporal 多进程 trace 或
Alertmanager receiver 已验收。生产必须使用 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` 指向外部 HTTPS
collector，并保存真实 trace delivery evidence。
