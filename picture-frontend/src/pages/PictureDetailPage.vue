<template>
  <div id="pictureDetailPage">
    <div class="detail-layout">
      <!-- 图片展示舞台：居中暗柔底色画框，凸显作品本身 -->
      <section class="detail-stage">
        <a-spin :spinning="loading">
          <div class="stage-frame">
            <a-image
              v-if="previewImageUrl"
              class="stage-image"
              :src="previewImageUrl"
              :alt="picture.name || '图片预览'"
            />
            <div v-else class="stage-placeholder">图片加载中</div>
          </div>
        </a-spin>
      </section>

      <!-- 右侧信息与操作面板 -->
      <aside class="detail-sidebar">
        <a-card :loading="loading" :bordered="false" class="info-card">
          <a-alert
            v-if="picture.id && reviewStatus !== 1"
            :message="`当前图片状态：${reviewStatusText}`"
            :description="reviewDescription"
            :type="reviewStatus === 2 ? 'error' : 'warning'"
            show-icon
            class="review-alert"
          />

          <div class="info-head">
            <span v-if="picture.category" class="category-chip">{{ picture.category }}</span>
            <h1 class="picture-title">{{ picture.name ?? '未命名' }}</h1>
            <p v-if="picture.introduction" class="picture-intro">{{ picture.introduction }}</p>
          </div>

          <!-- 作者行 -->
          <div class="author-row">
            <router-link
              v-if="picture.user?.id"
              :to="`/author/${picture.user.id}`"
              class="author-link"
              data-testid="picture-author-link"
            >
              <a-avatar
                :size="38"
                :src="picture.user?.userAvatar || undefined"
                class="author-avatar"
              >
                {{ (picture.user?.userName || '无').slice(0, 1) }}
              </a-avatar>
              <div class="author-meta">
                <div class="author-name">{{ picture.user?.userName || '未知作者' }}</div>
                <div class="author-sub">查看作者主页 →</div>
              </div>
            </router-link>
            <div v-else class="author-link is-static">
              <a-avatar
                :size="38"
                :src="picture.user?.userAvatar || undefined"
                class="author-avatar"
              >
                {{ (picture.user?.userName || '无').slice(0, 1) }}
              </a-avatar>
              <div class="author-meta">
                <div class="author-name">{{ picture.user?.userName || '未知作者' }}</div>
              </div>
            </div>
          </div>

          <!-- 主操作：下载 + 分享 + 收藏/点赞 -->
          <div class="primary-actions">
            <a-button type="primary" size="large" block @click="doDownload">
              <template #icon>
                <DownloadOutlined />
              </template>
              免费下载原图
            </a-button>
            <div class="secondary-actions">
              <InteractionButtons
                v-if="picture.id"
                :picture-id="String(picture.id)"
                :favorite-count="picture.favoriteCount"
                :like-count="picture.likeCount"
              />
              <a-button
                v-if="picture.reviewStatus === 1"
                data-testid="picture-share"
                @click="doShare"
              >
                <template #icon>
                  <ShareAltOutlined />
                </template>
                分享
              </a-button>
            </div>
            <div v-if="canEdit" class="owner-actions">
              <a-button type="default" size="small" @click="doEdit">
                <template #icon>
                  <EditOutlined />
                </template>
                编辑
              </a-button>
              <a-popconfirm
                title="确定删除这张图片吗？"
                ok-text="删除"
                cancel-text="取消"
                @confirm="doDelete"
              >
                <a-button danger size="small">
                  <template #icon>
                    <DeleteOutlined />
                  </template>
                  删除
                </a-button>
              </a-popconfirm>
            </div>
          </div>

          <!-- 标签 -->
          <div v-if="picture.tags?.length" class="meta-block">
            <div class="meta-heading">标签</div>
            <div class="tag-cloud">
              <a-tag v-for="tag in picture.tags" :key="tag" class="detail-tag">
                {{ tag }}
              </a-tag>
            </div>
          </div>

          <!-- 规格参数网格 -->
          <div class="meta-block">
            <div class="meta-heading">图片规格</div>
            <div class="spec-grid">
              <div class="spec-item">
                <span class="spec-label">尺寸</span>
                <span class="spec-value">
                  {{
                    picture.picWidth && picture.picHeight
                      ? `${picture.picWidth} × ${picture.picHeight}`
                      : '-'
                  }}
                </span>
              </div>
              <div class="spec-item">
                <span class="spec-label">格式</span>
                <span class="spec-value">{{ picture.picFormat ?? '-' }}</span>
              </div>
              <div class="spec-item">
                <span class="spec-label">大小</span>
                <span class="spec-value">{{ formatSize(picture.picSize) }}</span>
              </div>
              <div class="spec-item">
                <span class="spec-label">宽高比</span>
                <span class="spec-value">{{ picture.picScale ?? '-' }}</span>
              </div>
              <div class="spec-item">
                <span class="spec-label">下载次数</span>
                <span class="spec-value">{{ formatCount(picture.downloadCount) }}</span>
              </div>
              <div class="spec-item">
                <span class="spec-label">收藏 / 点赞</span>
                <span class="spec-value">
                  {{ formatCount(picture.favoriteCount) }} / {{ formatCount(picture.likeCount) }}
                </span>
              </div>
            </div>
          </div>
        </a-card>
      </aside>
    </div>

    <!-- 相似图片：后端用 imageVector 做 kNN，无数据时不渲染整个区块 -->
    <section v-if="similarPictures.length" class="similar-section" data-testid="similar-section">
      <div class="similar-head">
        <h2 class="similar-title">相似图片</h2>
        <span class="similar-sub">基于视觉特征推荐的相关画面</span>
      </div>
      <div class="similar-grid">
        <router-link
          v-for="item in similarPictures"
          :key="item.id"
          :to="`/picture/${item.id}`"
          class="similar-item"
          :data-testid="`similar-item-${item.id}`"
        >
          <div class="similar-cover-wrap">
            <img
              :src="getThumbnailUrl(item.url, 320)"
              :alt="item.name || '相似图片'"
              class="similar-cover"
              loading="lazy"
            />
          </div>
          <div class="similar-name">{{ item.name ?? '未命名' }}</div>
        </router-link>
      </div>
    </section>

    <!-- 分享弹窗：复制链接 / 二维码 / 系统分享 / 下载 -->
    <ShareModal v-model:open="shareOpen" :picture="picture" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  EditOutlined,
  DeleteOutlined,
  DownloadOutlined,
  ShareAltOutlined,
} from '@ant-design/icons-vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { getPictureVoById, deletePicture } from '@/api/pictureController'
import { listSimilarPictures } from '@/api/pictureExtra'
import { downloadPicture, formatSize, getShareImageUrl, getThumbnailUrl } from '@/utils'
import { formatCount } from '@/utils/interaction'
import { applySeoMeta, resetSeoMeta } from '@/utils/seo'
import InteractionButtons from '@/components/InteractionButtons.vue'
import ShareModal from '@/components/ShareModal.vue'

const loginUserStore = useLoginUserStore()
// 是否具有编辑权限
const canEdit = computed(() => {
  const loginUser = loginUserStore.loginUser
  // 未登录不可编辑
  if (!loginUser.id) {
    return false
  }
  // 仅本人或管理员可编辑
  const user = picture.value.user || {}
  return loginUser.id === user.id || loginUser.userRole === 'admin'
})

const props = defineProps<{
  id: string
}>()
const picture = ref<API.PictureVO>({})
const loading = ref(true)

// 详情页预览改用 1600px 缩略图：列表卡片只有 300~400px，详情页再拉原图会白白多传几 MB；
// 下载仍然走 downloadPicture(id)，拿到的始终是原图，不受这里影响。
const DETAIL_IMAGE_SIZE = 1600
const previewImageUrl = computed(() => getThumbnailUrl(picture.value.url, DETAIL_IMAGE_SIZE))

// 审核状态：0 待审核、1 已通过、2 已拒绝
const reviewStatus = computed(() => picture.value.reviewStatus ?? 1)

const reviewStatusText = computed(() => {
  const map: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已拒绝' }
  return map[reviewStatus.value] ?? '未知'
})

// 未通过时给出说明，管理员和作者本人才能看到该页
const reviewDescription = computed(() => {
  if (reviewStatus.value === 0) {
    return '该图片尚未通过审核，暂不会出现在首页列表中。'
  }
  if (reviewStatus.value === 2) {
    return picture.value.reviewMessage
      ? `拒绝原因：${picture.value.reviewMessage}`
      : '该图片未通过审核，暂不会出现在首页列表中。'
  }
  return ''
})

// 分享弹窗开关
const shareOpen = ref(false)

// 相似图片：没有数据时整个区块不渲染（RAG 关闭、源图无向量、召回为空都走这里）
const SIMILAR_LIMIT = 6
const similarPictures = ref<API.PictureVO[]>([])
// 快速切换详情页时，用自增 id 丢弃迟到的旧响应，避免把上一张图的相似结果渲染到新图
let similarRequestId = 0

const fetchSimilarPictures = async () => {
  const requestId = ++similarRequestId
  try {
    const res = await listSimilarPictures(props.id, SIMILAR_LIMIT)
    if (requestId !== similarRequestId) {
      return
    }
    const records = res.data.code === 0 && Array.isArray(res.data.data) ? res.data.data : []
    // 后端已排除源图，这里再兜一层，防止索引延迟返回自身
    similarPictures.value = records.filter((item) => String(item.id) !== String(props.id))
  } catch {
    if (requestId === similarRequestId) {
      similarPictures.value = []
    }
  }
}

// 获取图片详情
const fetchPictureDetail = async () => {
  loading.value = true
  // 切换图片时先清空旧数据，避免短暂显示上一张图的相似图
  similarPictures.value = []
  try {
    const res = await getPictureVoById({
      id: props.id,
    })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
      updateSeoMeta()
    } else {
      message.error('获取图片详情失败：' + (res.data.message || '图片不存在'))
      // 3秒后返回首页
      setTimeout(() => {
        router.push('/')
      }, 3000)
    }
  } catch (e: any) {
    message.error('获取图片详情失败：' + (e.message || '图片不存在'))
    // 3秒后返回首页
    setTimeout(() => {
      router.push('/')
    }, 3000)
  } finally {
    loading.value = false
  }
}

const router = useRouter()

// 编辑
const doEdit = () => {
  router.push('/add_picture?id=' + picture.value.id)
}
// 删除
const doDelete = async () => {
  const id = picture.value.id
  if (!id) {
    return
  }
  try {
    const res = await deletePicture({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      router.replace('/my_pictures')
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('删除失败：' + (error.message || '系统错误'))
  }
}

// 分享：打开弹窗（复制链接 / 二维码 / 系统分享）
const doShare = () => {
  shareOpen.value = true
}

/**
 * 人类访问时动态写 title 与 og:*：爬虫走 nginx 分流的后端分享页，
 * 这里保证浏览器标签、转发预览与后端卡片口径一致。
 */
const updateSeoMeta = () => {
  const name = picture.value.name || '未命名图片'
  const href = window.location.href
  const withoutHash = href.split('#')[0] ?? href
  applySeoMeta({
    title: `${name} - shifang37云图库`,
    description: picture.value.introduction || '',
    image: getShareImageUrl(picture.value.url),
    url: withoutHash.split('?')[0] ?? withoutHash,
  })
}

// 处理下载：直接走后端流式代理，后端在文件完整返回后按「用户/IP + 图片 + 天」去重计数
const doDownload = () => {
  downloadPicture(picture.value.id)
}

onMounted(() => {
  fetchPictureDetail()
  fetchSimilarPictures()
})

// 同一路由只切换 id 时组件会被复用：必须手动重新拉详情与相似图
watch(
  () => props.id,
  () => {
    fetchPictureDetail()
    fetchSimilarPictures()
  },
)

// 离开详情页时清掉动态 meta，避免标题/封面残留到下一个页面
onUnmounted(() => {
  resetSeoMeta()
})
</script>

<style scoped>
.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(320px, 0.95fr);
  gap: 24px;
  align-items: start;
}

.detail-stage {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  padding: 20px;
  box-shadow: var(--shadow-sm);
}

.stage-frame {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 440px;
  max-height: 680px;
  overflow: hidden;
  background:
    radial-gradient(circle at 50% 40%, rgba(255, 255, 255, 0.06), transparent 65%), #141418;
  border-radius: var(--radius-md);
}

.stage-frame :deep(.ant-image) {
  display: flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  max-height: 660px;
}

.stage-frame :deep(.ant-image-img) {
  max-width: 100%;
  max-height: 660px;
  object-fit: contain;
}

.stage-placeholder {
  color: rgba(255, 255, 255, 0.45);
  font-size: 13.5px;
}

.info-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.info-card :deep(.ant-card-body) {
  padding: 24px;
}

.review-alert {
  margin-bottom: 18px;
}

.info-head {
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}

.category-chip {
  display: inline-block;
  margin-bottom: 10px;
  padding: 3px 10px;
  color: var(--brand-600);
  font-weight: 600;
  font-size: 11.5px;
  background: var(--brand-soft);
  border-radius: 999px;
}

.picture-title {
  margin: 0;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 22px;
  line-height: 1.3;
  letter-spacing: -0.02em;
  word-break: break-word;
}

.picture-intro {
  margin: 10px 0 0;
  color: var(--ink-500);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.author-row {
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
}

.author-link {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 8px 10px;
  margin: -8px -10px;
  color: inherit;
  border-radius: var(--radius-md);
  transition: background-color 0.18s ease;
}

.author-link:not(.is-static):hover {
  color: inherit;
  background: var(--surface-muted);
}

.author-avatar {
  flex: none;
  color: #fff;
  font-weight: 600;
  background: var(--brand);
}

.author-meta {
  min-width: 0;
}

.author-name {
  color: var(--ink-900);
  font-weight: 600;
  font-size: 14.5px;
}

.author-sub {
  margin-top: 2px;
  color: var(--ink-400);
  font-size: 12px;
}

.primary-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 0;
  border-bottom: 1px solid var(--line);
}

.secondary-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.owner-actions {
  display: flex;
  gap: 8px;
  padding-top: 4px;
}

.meta-block {
  padding-top: 16px;
}

.meta-heading {
  margin-bottom: 10px;
  color: var(--ink-400);
  font-weight: 600;
  font-size: 11.5px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.detail-tag {
  margin: 0;
  color: var(--ink-700);
  background: var(--surface-subtle);
  border-color: transparent;
}

.spec-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.spec-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}

.spec-label {
  color: var(--ink-400);
  font-size: 11.5px;
}

.spec-value {
  color: var(--ink-900);
  font-weight: 600;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.similar-section {
  margin-top: 32px;
  padding: 24px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xs);
}

.similar-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 18px;
}

.similar-title {
  margin: 0;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 18px;
  letter-spacing: -0.02em;
}

.similar-sub {
  color: var(--ink-400);
  font-size: 13px;
}

.similar-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 16px;
}

.similar-item {
  display: block;
  color: inherit;
}

.similar-cover-wrap {
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--surface-subtle);
  border-radius: var(--radius-md);
}

.similar-cover {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1);
}

.similar-item:hover .similar-cover {
  transform: scale(1.05);
}

.similar-name {
  margin-top: 8px;
  overflow: hidden;
  color: var(--ink-700);
  font-weight: 500;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
  transition: color 0.18s ease;
}

.similar-item:hover .similar-name {
  color: var(--brand);
}

@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .similar-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .detail-stage {
    padding: 12px;
  }

  .stage-frame {
    min-height: 280px;
  }

  .similar-section {
    padding: 18px;
  }

  .similar-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}
</style>
