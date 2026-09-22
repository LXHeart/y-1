# 草场数字人运行时——CPU Fake 镜像（#105A-03 首创；H 阶段扩 GPU 与生产参数）
#
# 构建期：固定上游源码（bootstrap --fetch，仅 github/codeload）+ 冻结依赖（uv.lock --frozen）
# 运行期：以 grassland_dh.app 受控 app 启动（internal/audio 两 listener 由 compose 分开装配；
# 本镜像默认跑 internal 控制面，DH_ENABLED=false 时写路径一律 503 不派发 provider）。
#
# docker build 需访问 github 与 PyPI；镜像不含任何权重/密钥（K10：secret 由部署注入）。

FROM python:3.11-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    DH_ENABLED=false \
    DH_RENDERER_PROFILE=mock \
    DH_REAL_PROVIDERS_ENABLED=false

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

# 默认仅起 internal 控制面 listener；audio WS listener 由 compose 以同一镜像、
# 不同 entrypoint surface 参数装配（E 本地 compose；H 生产参数另行扩展）。
EXPOSE 9443
CMD ["uv", "run", "--frozen", "uvicorn", "grassland_dh.app:create_internal", "--factory", "--host", "0.0.0.0", "--port", "9443"]
