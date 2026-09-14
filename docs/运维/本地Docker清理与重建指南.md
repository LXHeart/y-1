# 本地 Docker 清理与重建指南

> 适用：本仓库的本地开发栈，主要面向 macOS + Docker Desktop。2026-09-15 按当前 Compose、Dockerfile 和本机 Buildx 帮助校准。
> 首次安装、凭据和端口配置见[根 README](../../README.md#快速开始)；生产操作见[生产发布与灾备运行手册](生产发布与灾备运行手册.md)。

所有命令从仓库根目录执行。示例显式读取 `.env.docker`；若实际使用其他环境文件或项目名，整组命令都要使用同一份 `--env-file`、`-p` 和 `-f` 参数。`.env.docker` 的准备方法见根 README，不要用生产配置重建本地栈。

## 一、先确认操作对象

```bash
docker context show
docker compose ls
docker compose --env-file .env.docker ps -a
docker system df -v
```

Docker 清理按容器、镜像、卷和构建缓存之间的引用关系判断候选，不判断数据是否还有业务价值：

| 对象 | 判断方式 | 删除影响 |
|---|---|---|
| 运行中或停止的容器 | 用 `docker inspect CONTAINER_ID` 核对项目标签、挂载和状态 | 删除容器会丢失可写层里的文件与容器日志；停止状态、随机名称都不代表可丢弃 |
| 镜像 | 核对是否被容器引用，是否是回退版本、基础镜像或近期要用的观测栈 | 清理后可能需要重新拉取或构建；`<none>` 只表示未打标签 |
| 卷 | 用 `docker volume inspect VOLUME_NAME` 核对标签，再查 Compose 声明和挂载 | 具名卷与匿名卷都可能保存数据；没有容器引用也不代表可删除 |
| 构建缓存 | 用 `docker buildx du` 查看当前 builder | 可回收，但可能让下一次构建重新下载依赖或执行耗时步骤 |

`RECLAIMABLE` 是 Docker 对可回收空间的估计，不是“业务上可以删除”的保证。当前 Compose 声明的卷可从[文件尾部](../../docker-compose.yml)核对，包括数据库、MinIO、Temporal、媒体临时目录和观测数据卷。

## 二、日常重建

### 构建范围与数据库迁移

当前 [Compose](../../docker-compose.yml) 有 **9 个带 `build:` 的服务**：

- 常规应用栈 8 个：`frontend`、`database-bootstrap`、`identity-service`、`edge-bff`、`marketplace-service`、`finance-service`、`trust-service`、`intelligence-service`。
- `release-migrator` 属于 `release` profile，供生产发布前顺序执行迁移，不在日常启动范围。
- PostgreSQL、Kafka、Redis、MinIO、Temporal 和观测栈使用现成镜像，不参与应用镜像构建。Compose 仍可能因配置或依赖变化重建这些容器，不能承诺依赖“完全不会被碰”。

Java Dockerfile 复制宿主机预先生成的 JAR。用 JDK 25 打包，再重建容器；单独重建镜像不会更新旧 JAR。`database-bootstrap` 初始化共享基础表，五个领域服务各自在启动时执行 Flyway 迁移；不能把全部迁移都归为 bootstrap。

### 执行步骤

先确认 JDK 并打包，任一步失败都先处理错误：

```bash
source scripts/lib/java-runtime.sh
ensure_java_runtime 25
./platform-java/gradlew -p platform-java bootJar
docker compose --env-file .env.docker config --quiet
```

使用默认本地数据库时，先确保它健康；连接外部开发数据库的环境跳过这一条：

```bash
docker compose --env-file .env.docker up -d --wait postgres-local
```

显式重建常规应用服务：

```bash
docker compose --env-file .env.docker up -d --build \
  database-bootstrap frontend identity-service edge-bff \
  marketplace-service finance-service trust-service intelligence-service
```

检查启动结果：

```bash
docker compose --env-file .env.docker ps -a
curl --fail --silent --show-error http://127.0.0.1:8080/health
```

常驻应用应达到 healthy；`database-bootstrap`、`minio-init` 是一次性任务，成功后退出是正常状态。异常时点名查看日志，例如：

```bash
docker compose --env-file .env.docker logs --tail 100 database-bootstrap intelligence-service
```

随后检查本次改动涉及的登录、业务读取或媒体上传。health 通过只说明入口可达，不替代业务验证。

`--profile` 放在子命令前，如 `docker compose --env-file .env.docker --profile observability up -d`。未指定服务的 `up` 会启动该 profile 的服务；仅需更新应用时使用上面的明确服务列表。

## 三、按对象清理

### 常用操作

清理前先看上一节的清单，保留确认提示；不要把全局清理自动接到每次构建后面。

```bash
# 悬空镜像：不清理仍被容器引用的镜像，可能减少可复用内容
docker image prune

# 只删除已确认完成的一次性容器；保留其卷
docker compose --env-file .env.docker rm database-bootstrap minio-init

# 点名删除已确认不再需要的镜像
docker image rm IMAGE_ID_OR_TAG
```

`docker container prune` 会删除当前 Docker context 中所有停止的容器，不限本项目。`docker system prune -a` 还会回收未使用的镜像、网络和构建缓存；添加 `--volumes` 会进一步涉及卷。这些都不属于默认日常步骤，不要根据“停止了”“名字随机”或“匿名卷”直接判定可删。

### 构建缓存

先查看当前版本支持的选项：

```bash
docker buildx du
docker buildx prune --help
```

当前本机 Buildx 可按目标占用清理：

```bash
docker buildx prune --max-used-space 10GB
```

`--max-used-space` 表示本次清理希望达到的缓存占用上限；正在使用或无法回收的缓存可能阻止达到目标。它不是持久 GC 配置，后续构建仍会增长。`--reserved-space` 表示可保留的空间，不能当作占用上限；旧版 `docker builder prune --keep-storage 10GB` 的支持情况以本机帮助为准。

10GB 是本地经验值，按磁盘与重建成本调整。清理后首次构建可能变慢，不保证仍能全部命中增量缓存。

### 清理隔离 E2E 项目

先核对测试运行时的项目名、环境文件和完整 Compose 文件组合。以下三个变量必须指向待销毁的隔离测试栈；有额外 overlay 时补齐对应的 `-f`：

```bash
docker compose -p "${E2E_PROJECT:?填写隔离测试项目名}" \
  --env-file "${E2E_ENV_FILE:?填写测试环境文件}" \
  -f "${E2E_COMPOSE_FILE:?填写测试使用的Compose文件}" ps -a

docker compose -p "${E2E_PROJECT:?}" --env-file "${E2E_ENV_FILE:?}" \
  -f "${E2E_COMPOSE_FILE:?}" down --volumes --rmi local
```

最后一条会删除该测试栈的容器、网络、非 external 卷和没有自定义 tag 的服务镜像。先保存所需日志与测试数据；不要对日常开发项目使用它。`--rmi all` 还会尝试删除服务使用的公共镜像，需要重新拉取，不作为默认值。

## 四、媒体卷权限异常

典型现象：Intelligence 的 multipart 上传报 `AccessDeniedException`，`/var/lib/grassland-media/tmp` 不可写。命名卷首次创建时会保留当时的目录内容和属主；后续镜像中的 `chown` 不会自动修复旧卷。

先检查当前容器用户和目录：

```bash
docker compose --env-file .env.docker exec intelligence-service id
docker compose --env-file .env.docker exec intelligence-service \
  ls -ld /var/lib/grassland-media /var/lib/grassland-media/tmp
```

确认该目录确实是本项目的 `intelligence_media_data` 挂载，且问题是当前 `grassland` 用户无写权限后，再修复：

```bash
docker compose --env-file .env.docker exec --user root intelligence-service \
  mkdir -p /var/lib/grassland-media/tmp
docker compose --env-file .env.docker exec --user root intelligence-service \
  chown -R grassland:grassland /var/lib/grassland-media
docker compose --env-file .env.docker restart intelligence-service
docker compose --env-file .env.docker exec intelligence-service \
  test -w /var/lib/grassland-media/tmp
```

使用容器内用户名，避免绑定历史 UID/GID；重启可让临时目录初始化重新执行。最后重试失败的上传，不能仅以属主显示正确作为修复完成的证据。

## 五、历史经验与维护

- 2026-08-21 曾因本机无法拉取 Docker Hub 镜像，在深度清理后影响重建；2026-09-02 曾恢复拉取。这些是当时的网络记录，不代表当前网络状态。判断可拉取性应以当前 daemon 对所需镜像的实际拉取结果为准。
- 2026-08 的磁盘回收量与冷构建耗时只适用于当时缓存，不能用于估算当前容量或承诺构建速度。
- 定期查看 `docker system df`，优先处理已结束的隔离测试栈，再按需处理缓存。不要删除数据卷来修复应用或镜像问题。
- 命令、服务数量与目录以 [Compose](../../docker-compose.yml)、[Intelligence Dockerfile](../../platform-java/services/intelligence-service/Dockerfile) 和 [Java 运行时工具](../../scripts/lib/java-runtime.sh) 为准；返回[运维索引](README.md)。
