# 本地 Docker 清理与重建指南

> 整理自 2026-08-21 的一次完整实践：三轮清理回收约 130GB + 全栈重建踩坑记录。
> 适用环境：macOS + Docker Desktop，本机网络无法直连 Docker Hub。

## 一、心智模型：Docker 靠「引用关系」判断东西有没有用

```
卷 (volume)  ←挂载─  容器 (container)  ←基于──  镜像 (image)  ←由层构成──  层 (layer)
                                    构建缓存 (build cache) ──→ 也持有层
```

- **运行中的容器**引用着它的镜像和卷 → 这条链上的东西永远安全
- 容器停了但还在（`docker ps -a` 可见）→ 镜像仍算被引用，prune 不删
- 容器删了且无其他容器用该镜像 → 镜像才是真正无引用
- `docker system df` 的 **RECLAIMABLE 列** = 官方算好的无引用可回收量

两条安全网：

1. prune 永远不删「还有容器引用（哪怕容器已停止）」的镜像
2. `docker rmi` 删被引用的镜像会直接报错拒绝（不加 `--force` 没有强删）
3. 顺序永远是**先清容器、再清镜像**

## 二、看账：三条命令

```bash
docker system df                              # 总账：各类占用与可回收量
docker ps -a --filter status=exited           # 停止的容器（清理候选）
docker images                                 # 全部镜像；<none>:<none> 是 dangling
```

## 三、判断规则

### 容器

| 状态 | 判断 |
|---|---|
| `Up` | 别动 |
| `Exited (0)` + compose 一次性任务（database-bootstrap、minio-init） | 能删，compose 下次 up 自动重建 |
| 随机名字（`recursing_pasteur` 之类） | 临时跑的遗留（多为 testcontainers），能删 |

### 镜像

| 类型 | 判断 |
|---|---|
| `<none>:<none>` dangling | 构建中间产物，随便删 |
| 整族项目前缀（如 `y1-e2e-*`） | e2e compose project 的产物，跑完就该整族删 |
| 同仓库多 tag 并存（tempo:2.9.0 / 2.9.1） | 跑哪个留哪个，其余可删（重下要过镜像源） |
| base 镜像（temurin / node / nginx 等） | **保留**，见第五节警告 |
| `y-1-*` 全家 + infra 镜像 | 在用，绝对留 |

### 卷

- 匿名 hash 名：基本都能删
- **具名卷删前先 grep compose 文件**（例：`y-1_prometheus_data` 被 compose 声明、服务未启动，看起来 dangling 实际有用）
- 删卷 = 删数据，务必确认

## 四、清理命令梯队（保守 → 激进）

```bash
# 1. 保守：停止的容器 + 悬空镜像，零风险
docker container prune -f
docker image prune -f

# 2. 精准删单个
docker rmi <repo:tag>

# 3. 项目级整删（e2e 跑完的根治姿势，容器+网络+镜像+卷一次带走）
docker compose -p <e2e项目名> down --rmi all -v

# 4. 构建缓存（大头；代价 = 下次全量构建变慢）
docker builder prune -a -f

# 5. 一条龙（= 1 + 4 的容器/网络/镜像部分；不含卷，加 --volumes 才动卷，慎用）
docker system prune -a -f
```

### ⚠️ 本机特有警告

**不要用 `docker image prune -a`**。它会删掉 base 镜像（eclipse-temurin:25-jre-alpine、node:20-bookworm、nginx:1.27-alpine，合计约 1.7GB），而本机无法直连 Docker Hub（`auth.docker.io` 超时），删了就拉不回来，下次构建直接失败——2026-08-21 实际踩过：全量重建卡在 `failed to fetch anonymous token ... i/o timeout`，靠镜像源手动补拉才恢复。

base 镜像需要补拉时的姿势（走 daocloud 镜像源再 retag）：

```bash
for img in eclipse-temurin:25-jre-alpine nginx:1.27-alpine node:20-bookworm; do
  docker pull "docker.m.daocloud.io/library/$img" \
    && docker tag "docker.m.daocloud.io/library/$img" "$img" \
    && docker rmi "docker.m.daocloud.io/library/$img"
done
```

根治办法（改一次一劳永逸）：Docker Desktop → Settings → Docker Engine 加：

```json
"registry-mirrors": ["https://docker.m.daocloud.io"]
```

## 五、重建：日常只需要动自己的服务

### 关键机制

- compose 里**只有 8 个服务带 `build:` 段**（frontend + 7 个 Java），基础设施（postgres/kafka/redis/minio/temporal/观测栈）全是 `image:` 直接用现成镜像，**永远不参与构建**，`--build` 只作用于有 build 段的服务
- infra 容器偶尔显示 `Recreate` 是**配置对账**（compose 文件改过 → 配置哈希变了），秒级换容器，不是构建
- **jar 必须宿主机预构建**（Dockerfile 设计如此，避免容器内 gradle 联网）；数据在卷里，容器重建不丢；新 Flyway 迁移由 database-bootstrap 在启动链上自动跑
- `--profile observability` 会把该 profile 下所有服务拉起（包括没在跑的 alertmanager），**日常重建不要带**

### 日常两步

```bash
# 1. 构建最新 jar（必须 JDK 25）
cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew bootJar

# 2. 只重建自己的服务（infra 只被确保在跑，不会被碰；database-bootstrap 在依赖链上自动跑）
cd .. && docker compose up -d --build \
  frontend identity-service edge-bff \
  marketplace-service finance-service trust-service intelligence-service
```

注意 `--profile` 是全局 flag，必须放在子命令前：`docker compose --profile observability up -d --build`（放后面报 `unknown flag`）。

### 建议加 zsh 函数（~/.zshrc，2026-08-28 已装，清理已内置）

```bash
y1-rebuild() {
  cd ~/claude/y-1/platform-java \
    && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew bootJar \
    && cd .. && docker compose up -d --build \
      frontend identity-service edge-bff \
      marketplace-service finance-service trust-service intelligence-service "$@" \
    && docker image prune -f \
    && docker builder prune -af --keep-storage 10GB
}
```

之后改完代码 `y1-rebuild` 一条命令搞定，重建 + 清理一步到位（清理逻辑见下节）。

### ⚠️ 命名卷的属主漂移坑（2026-08-26 实录）

`intelligence-service` 挂了命名卷 `intelligence_media_data:/var/lib/grassland-media`，Dockerfile 里对它做过
`mkdir + chown grassland`（fd7ba5e，2026-08-10）。但**命名卷只在首次创建时从镜像拷贝内容与属主**——卷创建早于
该 Dockerfile 变更的机器（本机正是），卷里目录的属主停留在旧镜像的 `997:997`，重建镜像/容器**永远不会**修正它。

后果：Spring multipart 落盘目录（`-Djava.io.tmpdir=/var/lib/grassland-media/tmp`）不可写，任何带
>32KB 图片的请求（`/api/image-analysis/step/draft`、video-recreation 等 multipart 流）一律
`AccessDeniedException` → **500 Internal Server Error**；且 `FileStorage$TempFileStorage` 缓存了目录解析，
**chown 之后必须重启容器**才生效。

一次性修复（已在本机执行过，新机若复刻同路径踩坑时再用）：

```bash
docker exec -u root y-1-intelligence-service-1 chown -R 100:101 /var/lib/grassland-media
docker restart y-1-intelligence-service-1
```

判断是否中招：`docker exec y-1-intelligence-service-1 ls -ld /var/lib/grassland-media/tmp` 属主不是
`grassland grassland` 即中招。全新机器（卷首次从当前镜像初始化）不会遇到。

## 六、防再堆积的习惯

1. **每次重建后顺手清**（2026-08-28 起内置进 `y1-rebuild`，手动跑这两条也行）：
   ```bash
   docker image prune -f                        # 只删悬空镜像，零风险（绝不用 -a）
   docker builder prune -af --keep-storage 10GB # 构建缓存封顶 10GB，只逐出最旧的
   ```
   逻辑：悬空镜像是每次重建必然产生的（旧 tag 被顶掉变 `<none>`），不清就会攒；
   构建缓存保留最近 10GB 足够增量构建秒级命中，超出部分是最旧的、早被新构建作废，
   逐出不拖慢下次构建——这样既不回到 2026-08-28 之前 66GB 缓存的状态，也不付出每次全量冷构建 4 分钟的代价。
2. **e2e 跑完随手整删**：`docker compose -p <e2e项目名> down --rmi all -v`。历史垃圾最大来源就是 e2e 每次换项目名（y1-e2e-mtlsfix / final3 / final4 / local...）留下一整套镜像
3. 每隔一阵 `docker system df` 看一眼 RECLAIMABLE，超过 10GB 再动手；构建缓存是正常的构建加速设施，不必次次清零（有了 keep-storage 封顶后这条基本不会再触发）
4. 清完构建缓存后第一次重建必然全量、明显变慢（一次性代价），之后恢复增量

## 附：2026-08-21 实测数据参考

| 轮次 | 动作 | 效果 |
|---|---|---|
| 第一轮 | container prune + image prune -a + builder prune + 卷清理 | 122→12 镜像，总占用约 110GB → 4.6GB |
| 第二轮（7 小时后） | 同上 | e2e 又留了 16 个无引用镜像 + 18GB 构建缓存 |
| 全量重建 | 补拉 3 个 base 镜像 + 重建 8 个服务镜像 | 冷缓存全量构建约 4 分钟，全栈 healthy |

注意第一轮里 `image prune -a` 在当时是安全的（当时 Docker Hub 尚可达或镜像尚在）；**在本机当前网络环境下该命令已列入禁区**，以第四节警告为准。
