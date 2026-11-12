<template>
  <a-modal
    :open="open"
    title="分享图片"
    :footer="null"
    :width="420"
    @update:open="(value: boolean) => emit('update:open', value)"
  >
    <div class="share-body" data-testid="share-modal">
      <div class="share-link-row">
        <a-input :value="shareUrl" readonly data-testid="share-link-input" />
        <a-button type="primary" data-testid="share-copy" @click="copyLink">复制链接</a-button>
      </div>

      <!-- 二维码按需生成：qrcode 库在打开弹窗时才动态加载，不进入首屏包 -->
      <div class="share-qr" data-testid="share-qr">
        <a-spin v-if="qrLoading" />
        <img v-else-if="qrDataUrl" :src="qrDataUrl" alt="分享二维码" />
        <span v-else class="share-qr-fallback">二维码不可用，可直接复制链接</span>
      </div>

      <a-space wrap>
        <a-button v-if="canNativeShare" data-testid="share-native" @click="nativeShare">
          系统分享
        </a-button>
        <a-button data-testid="share-download" @click="download">下载图片</a-button>
      </a-space>
      <p class="share-tip">复制链接发到微信 / Twitter 时，卡片会带上标题、简介与封面</p>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { downloadPicture } from '@/utils'

/**
 * 分享弹窗：复制链接 + 二维码 + 系统分享 + 下载原图。
 *
 * 二维码用 `qrcode` 动态 import：库本身约 20KB，只在真正打开分享弹窗时才加载。
 * 分享地址取当前页面并去掉查询串，避免把站内筛选条件一起分享出去。
 */
const props = defineProps<{
  open: boolean
  picture: API.PictureVO
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
}>()

const shareUrl = ref('')
const qrDataUrl = ref('')
const qrLoading = ref(false)

const canNativeShare = computed(
  () => typeof navigator !== 'undefined' && typeof navigator.share === 'function',
)

const pageUrl = () => {
  if (typeof window === 'undefined') {
    return ''
  }
  const href = window.location.href
  const withoutHash = href.split('#')[0] ?? href
  return withoutHash.split('?')[0] ?? withoutHash
}

const generateQrCode = async (url: string) => {
  if (!url) {
    qrDataUrl.value = ''
    return
  }
  qrLoading.value = true
  qrDataUrl.value = ''
  try {
    const { toDataURL } = await import('qrcode')
    qrDataUrl.value = await toDataURL(url, { width: 200, margin: 1 })
  } catch {
    // 生成失败时仍然可以复制链接，不打扰用户
    qrDataUrl.value = ''
  } finally {
    qrLoading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    shareUrl.value = pageUrl()
    generateQrCode(shareUrl.value)
  },
)

/**
 * 复制链接：优先用 Clipboard API，不可用（非 HTTPS / 老浏览器）时退回复制文本域方案
 */
const copyText = async (text: string) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return true
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'readonly')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  return copied
}

const copyLink = async () => {
  try {
    await copyText(shareUrl.value)
    message.success('链接已复制，可以分享给他人了')
  } catch {
    message.info('复制失败，请手动复制：' + shareUrl.value)
  }
}

const nativeShare = async () => {
  try {
    await navigator.share({
      title: props.picture.name || '云图库图片',
      text: props.picture.introduction || '',
      url: shareUrl.value,
    })
  } catch {
    // 用户取消分享（AbortError）也走这里，保持静默
  }
}

const download = () => {
  downloadPicture(props.picture.id)
}
</script>

<style scoped>
.share-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
}

.share-link-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.share-qr {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}

.share-qr img {
  width: 200px;
  height: 200px;
}

.share-qr-fallback {
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}

.share-tip {
  margin: 0;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  text-align: center;
}
</style>
