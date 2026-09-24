# 草场数字人运行时——CPU Fake/媒体网关镜像（#105A-03 首创；#105H C105H-01 收口：
# 非 root + 双 surface 装配入口 + 端口契约固化）。
#
# 构建期：固定上游源码（bootstrap --fetch，仅 github/codeload）+ 冻结依赖（uv.lock --frozen）
# 运行期：以 grassland_dh.app 受控 app 启动（本镜像是无推理栈 CPU 媒体网关/Fake 目标：
# 不含任何模型权重/CUDA/GPU 驱动要求，K14.4；真实渲染由第三方服务承担，凭据不进本镜像）。
#
# docker build 需访问 github 与 PyPI；镜像不含任何权重/密钥（K10：secret 由部署注入）。

FROM python:3.11-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DH_ENABLED=false \
    DH_RENDERER_PROFILE=mock \
    DH_REAL_PROVIDERS_ENABLED=false \
    # 非 root 运行：uv 只写 /tmp（缓存/HOME），项目树只读。
    UV_CACHE_DIR=/tmp/uv-cache

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app/digital-human

# 依赖层（先锁文件，最大化缓存）
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project

# 项目层：源码/补丁/引导脚本（src 为显式路径引入，项目自身不安装）
COPY src ./src
COPY patches ./patches
COPY scripts ./scripts
COPY upstream.lock.json ./

# 固定上游源码 + 受限补丁 overlay（vendor 与 .runtime-overlay 均为运行期产物，不入镜像层标签）
RUN uv run --frozen python scripts/bootstrap.py --fetch \
 && uv run --frozen python -c "import sys; sys.path.insert(0, 'src'); \
    from grassland_dh.adapters import ensure_runtime_overlay; ensure_runtime_overlay()"

ENV PYTHONPATH=/app/digital-human/src:/app/digital-human/vendor/.runtime-overlay

# 双 surface 装配入口（#105H C105H-01 / K13.2）：音频面（DH_RUNTIME_AUDIO_PORT=9080）恒为
# 前台主进程（公开 WS 只挂精确 audio 路径 + health）；内部控制面 mTLS（DH_RUNTIME_CONTROL_PORT=9443）
# 仅在证书三件套（cert/key/CA secret 文件）齐备时后台启动——DH 关闭或未注入证书时不空占端口。
COPY <<'ENTRYPOINT_EOF' /usr/local/bin/dh-entrypoint.sh
#!/bin/sh
set -eu

audio_port="${DH_RUNTIME_AUDIO_PORT:-9080}"
internal_pid=""

if [ -n "${DH_INTERNAL_CLIENT_CERT_FILE:-}" ] && [ -s "${DH_INTERNAL_CLIENT_CERT_FILE}" ] \
   && [ -s "${DH_INTERNAL_CLIENT_KEY_FILE:-}" ] && [ -s "${DH_INTERNAL_CA_FILE:-}" ]; then
  echo "dh-entrypoint: starting internal mTLS surface on ${DH_RUNTIME_CONTROL_PORT:-9443}" >&2
  uv run --frozen python -c "import os; from grassland_dh.app import start_internal_mtls; start_internal_mtls(os.environ['DH_INTERNAL_CLIENT_CERT_FILE'], os.environ['DH_INTERNAL_CLIENT_KEY_FILE'], os.environ['DH_INTERNAL_CA_FILE'], port=int(os.environ.get('DH_RUNTIME_CONTROL_PORT', '9443')))" &
  internal_pid=$!
else
  echo "dh-entrypoint: internal mTLS certs not provided; audio surface only" >&2
fi

uv run --frozen uvicorn grassland_dh.app:create_audio --factory --host 0.0.0.0 --port "$audio_port" &
audio_pid=$!

shutdown() {
  kill -TERM "$audio_pid" 2>/dev/null || true
  if [ -n "$internal_pid" ]; then
    kill -TERM "$internal_pid" 2>/dev/null || true
  fi
  wait || true
  exit 0
}
trap shutdown TERM INT

wait "$audio_pid"
shutdown
ENTRYPOINT_EOF
RUN chmod 0755 /usr/local/bin/dh-entrypoint.sh

# 非 root 运行（K10/H01）：专用系统账号，项目树只读、可写仅 /tmp（uv 缓存/HOME）。
# 注意构建期（root）已按同一 UV_CACHE_DIR 建立缓存目录，须一并改属主，否则运行期
# 非 root 的 `uv run` 初始化缓存时 Permission denied 直接退出。
RUN addgroup --system --gid 10001 dh \
 && adduser --system --uid 10001 --ingroup dh --home /tmp --no-create-home --shell /usr/sbin/nologin dh \
 && mkdir -p /tmp/uv-cache \
 && chown -R 10001:10001 /app/digital-human /tmp/uv-cache
USER 10001:10001

# 端口契约（K13.2）：9443 = internal 控制 mTLS（默认不启动）；9080 = 公开音频 WS（由 Nginx
# 精确 location 升级，仅容器网络可达，不发布到宿主）。
EXPOSE 9443 9080
ENTRYPOINT ["/usr/local/bin/dh-entrypoint.sh"]
