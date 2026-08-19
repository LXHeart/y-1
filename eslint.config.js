import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import globals from 'globals'

/**
 * 前端 ESLint 门禁（2026-08-20 首次引入）。
 *
 * 刻意只开「错误预防」级规则集（vue/flat/essential + ts recommended），不开
 * stylistic/formatting——避免一次性对存量 200+ 文件产生海量格式 diff；格式化
 * （Prettier/Spotless）与更严的规则档位随后续批次逐步收紧。
 */
export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'playwright-report/**',
      'test-artifacts/**',
      'tmp/**',
      'platform-java/**',
      'public/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    languageOptions: {
      globals: { ...globals.browser },
    },
  },
  {
    // scripts/ 下是 tsx 驱动的 Node 脚本（CI 质量门/部署校验）。
    files: ['scripts/**/*.ts', 'test/**/*.ts'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
  },
  {
    // TS 的未定义符号由 vue-tsc --noEmit 把守（CI node job 已跑）；
    // eslint 的 no-undef 不理解类型注解，会把 CanvasImageSource 这类 DOM 类型误报为未定义。
    files: ['**/*.ts', '**/*.vue'],
    rules: {
      'no-undef': 'off',
    },
  },
  {
    rules: {
      // 存量代码库尚有少量 any/空 catch（.best-effort 清理路径），先降为 warn 不阻塞 CI；
      // 待清零后恢复 error。
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-empty': ['error', { allowEmptyCatch: true }],
    },
  },
)
