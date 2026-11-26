<template>
  <div id="askPage">
    <section class="ask-hero">
      <p class="ask-eyebrow">RAG · 带引用问答</p>
      <h1 class="ask-title">用一句话，问遍整个图库</h1>
      <p class="ask-sub">
        回答只依据公开图片，引用可点回详情；没有相关图片时会明确拒答，不会编造。
      </p>
    </section>

    <a-alert
      v-if="configReady && !ragConfig.askEnabled"
      class="ask-disabled"
      type="info"
      show-icon
      message="图库问答未启用"
      description="当前环境没有开启 RAG 或 Elasticsearch，先浏览首页图片吧。"
    >
      <template #action>
        <router-link to="/">
          <a-button size="small">回首页</a-button>
        </router-link>
      </template>
    </a-alert>

    <a-card v-else-if="configReady" class="ask-card">
      <a-textarea
        v-model:value="question"
        :maxlength="MAX_QUESTION"
        :rows="3"
        show-count
        allow-clear
        placeholder="例如：哪些图片适合做咖啡店海报？"
        data-testid="ask-input"
        @press-enter.prevent="doAsk()"
      />
      <div class="ask-actions">
        <span class="ask-limit">
          每人每天 {{ ragConfig.askDailyLimit }} 次 · 回答仅基于公开图库
        </span>
        <a-button
          type="primary"
          size="large"
          :loading="asking"
          :disabled="!question.trim()"
          data-testid="ask-submit"
          @click="doAsk()"
        >
          提问
        </a-button>
      </div>
      <div class="ask-samples">
        <span class="ask-samples-label">试试：</span>
        <a-tag
          v-for="sample in SAMPLE_QUESTIONS"
          :key="sample"
          class="ask-sample"
          data-testid="ask-sample"
          @click="doAsk(sample)"
        >
          {{ sample }}
        </a-tag>
      </div>
    </a-card>

    <a-alert
      v-if="errorMessage"
      class="ask-error"
      :type="limited ? 'warning' : 'error'"
      show-icon
      :message="errorMessage"
      data-testid="ask-error"
    />

    <a-spin :spinning="asking" class="ask-result-spin">
      <template v-if="result">
        <a-card class="ask-answer-card" data-testid="ask-answer">
          <template #title>
            <span class="ask-answer-title">回答</span>
            <a-tag v-if="result.degraded" color="orange" class="ask-state-tag">降级</a-tag>
            <a-tag v-else-if="result.refused" class="ask-state-tag">拒答</a-tag>
            <a-tag v-else color="green" class="ask-state-tag"
              >已引用 {{ citations.length }} 张</a-tag
            >
          </template>
          <p class="ask-answer-text">{{ result.answer || '没有找到相关图片。' }}</p>
          <a-alert
            v-if="result.degraded"
            type="warning"
            show-icon
            message="问答服务暂时不可用，已返回固定提示；可以稍后重试或先浏览图片。"
            data-testid="ask-degraded"
          />
          <a-empty
            v-else-if="result.refused && !citations.length"
            description="没有找到与问题相关的公开图片，换个问法或先浏览图库"
            data-testid="ask-refused"
          />
        </a-card>

        <a-card
          v-if="citations.length"
          class="ask-citation-card"
          title="引用图片"
          data-testid="ask-citations"
        >
          <a-row :gutter="[12, 12]">
            <a-col v-for="citation in citations" :key="citation.id" :xs="12" :sm="8" :md="6">
              <router-link
                :to="`/picture/${citation.id}`"
                class="citation-item"
                :data-testid="`citation-${citation.id}`"
              >
                <img
                  :src="getThumbnailUrl(citation.thumbnailUrl || citation.pictureUrl, 400)"
                  :alt="citation.name || '引用图片'"
                  class="citation-cover"
                  loading="lazy"
                />
                <div class="citation-name">{{ citation.name || '未命名' }}</div>
                <p v-if="citation.reason" class="citation-reason">{{ citation.reason }}</p>
              </router-link>
            </a-col>
          </a-row>
        </a-card>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { askRag, type RagAskResult, type RagCitation } from '@/api/ragExtra'
import { useRagConfig } from '@/composables/useRagConfig'
import { getThumbnailUrl } from '@/utils'

// 与后端 RagAskRequest 的 @Size(max = 200) 保持一致
const MAX_QUESTION = 200
const SAMPLE_QUESTIONS = [
  '哪些图片适合做竖版海报？',
  '暖色调的日落海边照片',
  '适合做手机壁纸的星空',
  '黑白线条简笔画的动物',
]

const { ragConfig, loadRagConfig } = useRagConfig()

const question = ref('')
const asking = ref(false)
const result = ref<RagAskResult | null>(null)
const errorMessage = ref('')
const limited = ref(false)
// 配置加载完成前不渲染「未启用」或输入框，避免直开 /ask 时先闪一下错误态
const configReady = ref(false)
// 模板里用 length 判断引用数量，避免每处都写可选链
const citations = computed<RagCitation[]>(() =>
  Array.isArray(result.value?.citations) ? result.value.citations : [],
)

const doAsk = async (sample?: string) => {
  if (sample) {
    question.value = sample
  }
  const text = question.value.trim()
  if (!text || asking.value) {
    return
  }
  asking.value = true
  errorMessage.value = ''
  limited.value = false
  result.value = null
  try {
    const res = await askRag(text)
    if (res.data.code === 0) {
      result.value = res.data.data ?? {}
    } else if (res.data.code === 42900) {
      // 超限：保留后端的明确提示，不显示成通用错误
      limited.value = true
      errorMessage.value = res.data.message || '今日问答次数已用完，请明天再试'
    } else {
      errorMessage.value = res.data.message || '问答失败，请稍后重试'
    }
  } catch (error: any) {
    errorMessage.value = error?.message || '网络异常，请稍后重试'
  } finally {
    asking.value = false
  }
}

onMounted(async () => {
  // 成功或失败都要结束等待：失败时按默认关闭展示说明，而不是一直空白
  try {
    await loadRagConfig()
  } finally {
    configReady.value = true
  }
})
</script>

<style scoped>
#askPage {
  max-width: 920px;
  margin: 0 auto;
}

.ask-hero {
  padding: 16px 0 28px;
  text-align: center;
}

.ask-eyebrow {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  margin: 0 0 14px;
  padding: 5px 14px;
  color: var(--brand-600);
  font-weight: 600;
  font-size: 11.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  box-shadow: var(--shadow-xs);
}

.ask-title {
  margin: 0 0 12px;
  color: var(--ink-900);
  font-weight: 800;
  font-size: clamp(28px, 3.8vw, 40px);
  letter-spacing: -0.035em;
}

.ask-sub {
  max-width: 600px;
  margin: 0 auto;
  color: var(--ink-500);
  font-size: 14.5px;
  line-height: 1.72;
}

.ask-card {
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
}

.ask-card :deep(.ant-card-body) {
  padding: 22px;
}

.ask-card :deep(textarea.ant-input) {
  font-size: 15px;
  line-height: 1.65;
}

.ask-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 16px;
}

.ask-actions :deep(.ant-btn) {
  padding-inline: 28px;
  border-radius: 999px;
}

.ask-limit {
  color: var(--ink-400);
  font-size: 12.5px;
}

.ask-samples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}

.ask-samples-label {
  color: var(--ink-400);
  font-size: 12.5px;
}

.ask-sample {
  margin-inline-end: 0;
  padding: 3px 12px;
  color: var(--ink-700);
  background: var(--surface-muted);
  border: 1px solid var(--line);
  cursor: pointer;
  user-select: none;
  transition:
    color 0.18s ease,
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.ask-sample:hover {
  color: var(--brand-600);
  background: var(--brand-soft);
  border-color: var(--brand-ring);
}

.ask-error,
.ask-result-spin {
  display: block;
  margin-top: 20px;
}

.ask-result-spin :deep(.ant-spin-container) {
  min-height: 1px;
}

.ask-answer-card {
  margin-top: 18px;
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.ask-answer-title {
  font-weight: 700;
}

.ask-state-tag {
  margin-left: 8px;
}

.ask-answer-text {
  margin: 0;
  color: var(--ink-900);
  font-size: 15.5px;
  line-height: 1.85;
  white-space: pre-wrap;
}

.ask-answer-card :deep(.ant-alert),
.ask-answer-card :deep(.ant-empty) {
  margin-top: 14px;
}

.ask-citation-card {
  margin-top: 18px;
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.citation-item {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 10px;
  color: inherit;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

.citation-item:hover {
  color: inherit;
  background: var(--surface);
  border-color: var(--brand-ring);
  box-shadow: var(--shadow-md);
  transform: translateY(-3px);
}

.citation-cover {
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  background: var(--surface-subtle);
  border-radius: var(--radius-sm);
}

.citation-name {
  margin-top: 10px;
  overflow: hidden;
  color: var(--ink-900);
  font-weight: 600;
  font-size: 13.5px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.citation-reason {
  display: -webkit-box;
  margin: 4px 0 0;
  overflow: hidden;
  color: var(--ink-500);
  font-size: 12px;
  line-height: 1.55;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ask-disabled {
  margin-top: 8px;
  border-radius: var(--radius-md);
}

@media (max-width: 720px) {
  .ask-hero {
    padding: 8px 0 20px;
  }

  .ask-card :deep(.ant-card-body) {
    padding: 16px;
  }

  .ask-actions {
    align-items: stretch;
  }

  .ask-actions :deep(.ant-btn) {
    width: 100%;
  }
}
</style>
