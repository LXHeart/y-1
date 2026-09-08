# 测试目录

仓库级自动化测试统一使用 `tests/`。前端业务单测仍与 `src/` 源码相邻，Java 测试仍位于各 Gradle 模块的 `src/test/`。

```text
tests/
├── deployment/       部署、CI、运行时及前后端边界契约（Vitest）
├── quality/          覆盖率工具单测（Vitest）
├── security/         密钥扫描器单测（Vitest）
├── e2e/              公共入口浏览器测试（Playwright）
└── setup-env.ts      Vitest 公共初始化
```

从仓库根目录执行：

```bash
npm test                                      # src/**/*.test.ts + tests/**/*.test.ts
npm test -- tests/deployment                   # 仅部署契约
npm run test:coverage                          # 单测与前端覆盖率
npm run e2e -- --list                          # 检查 Playwright 用例发现
npm run e2e -- --project=chromium               # 对已启动的测试环境运行浏览器用例
npm run e2e:ci                                # 完整隔离 Compose E2E
```

部署契约会调用 Docker Compose 配置解析，但不会因目录整理而启动业务验收脚本。浏览器测试的环境准备沿用根 `README.md` 与 `scripts/ci-e2e.sh`。

手工验收脚本在 `scripts/acceptance/`，本机临时脚本在被 Git 忽略的 `scripts/local/`。测试产物沿用 `coverage/`、`playwright-report/` 和 `test-artifacts/`。
