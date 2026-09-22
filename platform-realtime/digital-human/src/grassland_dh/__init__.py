"""grassland_dh：草场数字人工作台受控实时域（#105A-03）。

本包 import 无副作用：不联网、不下载模型、不读用户全局环境、不启动任何 provider。
上游 OpenTalking 以 vendor 源码 + 运行时 overlay（adapters.ensure_runtime_overlay）
方式接入，绝不启动原生 apps.api.main。
"""

__version__ = "0.1.0"
