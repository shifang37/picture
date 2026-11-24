import { computed, ref } from 'vue'
import { fetchRagConfig, type RagConfig } from '@/api/ragExtra'

/**
 * RAG 开关的全局单例。
 *
 * 首页、顶栏、问答页、管理端都需要判断「现在能不能用 hybrid / ask」，但配置随部署环境变化，
 * 不能写死在前端构建产物里。这里只在首次使用时请求一次 `GET /api/rag/config`，
 * 失败时按「全部关闭」处理，不阻塞页面主流程；未成功加载时下一次进入仍会重试。
 */
const DEFAULT_CONFIG: Required<RagConfig> = {
  enabled: false,
  hybridSearch: false,
  askEnabled: false,
  askDailyLimit: 10,
  askTopK: 8,
}

const config = ref<RagConfig>({ ...DEFAULT_CONFIG })
const loaded = ref(false)
let pending: Promise<void> | null = null

const normalize = (raw?: RagConfig): Required<RagConfig> => ({
  enabled: Boolean(raw?.enabled),
  hybridSearch: Boolean(raw?.hybridSearch),
  askEnabled: Boolean(raw?.askEnabled),
  askDailyLimit:
    typeof raw?.askDailyLimit === 'number' && raw.askDailyLimit > 0
      ? raw.askDailyLimit
      : DEFAULT_CONFIG.askDailyLimit,
  askTopK:
    typeof raw?.askTopK === 'number' && raw.askTopK > 0 ? raw.askTopK : DEFAULT_CONFIG.askTopK,
})

/**
 * 加载 RAG 开关；并发调用共享同一个请求，`force` 为 true 时重新拉取。
 */
export async function loadRagConfig(force = false): Promise<void> {
  if (loaded.value && !force) {
    return
  }
  if (pending) {
    return pending
  }
  pending = (async () => {
    try {
      const res = await fetchRagConfig()
      if (res.data.code === 0 && res.data.data) {
        config.value = normalize(res.data.data)
        loaded.value = true
      }
    } catch {
      // 配置接口不可用时保持「全部关闭」，首页 / 详情等主流程不受影响
    } finally {
      pending = null
    }
  })()
  return pending
}

/** 供组件直接使用的只读状态与加载函数 */
export function useRagConfig() {
  return {
    ragConfig: config,
    ragConfigLoaded: computed(() => loaded.value),
    loadRagConfig,
  }
}

/** 测试或退出登录后重置（普通页面不需要调用） */
export function resetRagConfig() {
  config.value = { ...DEFAULT_CONFIG }
  loaded.value = false
  pending = null
}
