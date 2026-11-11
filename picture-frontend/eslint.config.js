import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{ts,mts,tsx,vue}'],
  },
  {
    name: 'app/files-to-ignore',
    ignores: [
      '**/dist/**',
      '**/coverage/**',
      '**/node_modules/**',
      'components.d.ts',
      // @umijs/openapi 生成的文件，每次 npm run openapi 都会重写，不参与 lint
      'src/api/*Controller.ts',
      'src/api/index.ts',
    ],
  },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  skipFormatting,
  {
    name: 'app/rules',
    rules: {
      // 组件以目录语义命名（如 layouts/BasicLayout），不强制多单词
      'vue/multi-word-component-names': 'off',
      // 历史代码里 catch 变量多为 any，首次接入 ESLint 先不把它当错误
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
