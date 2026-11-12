<template>
  <div id="pictureDetailPage">
    <a-row :gutter="[16, 16]">
      <!-- 图片展示区 -->
      <a-col :sm="24" :md="16" :xl="18">
        <a-card title="图片预览" :loading="loading">
          <a-image style="max-height: 600px; object-fit: contain" :src="picture.url" />
        </a-card>
      </a-col>
      <!-- 图片信息区 -->
      <a-col :sm="24" :md="8" :xl="6">
        <a-card title="图片信息" :loading="loading">
          <a-alert
            v-if="picture.id && reviewStatus !== 1"
            :message="`当前图片状态：${reviewStatusText}`"
            :description="reviewDescription"
            :type="reviewStatus === 2 ? 'error' : 'warning'"
            show-icon
            style="margin-bottom: 16px"
          />
          <a-descriptions :column="1">
            <a-descriptions-item label="作者">
              <router-link
                v-if="picture.user?.id"
                :to="`/author/${picture.user.id}`"
                class="author-link"
                data-testid="picture-author-link"
              >
                <a-avatar :size="24" :src="picture.user?.userAvatar" />
                <span>{{ picture.user?.userName }}</span>
              </router-link>
              <a-space v-else>
                <a-avatar :size="24" :src="picture.user?.userAvatar" />
                <div>{{ picture.user?.userName }}</div>
              </a-space>
            </a-descriptions-item>
            <a-descriptions-item label="名称">
              {{ picture.name ?? '未命名' }}
            </a-descriptions-item>
            <a-descriptions-item label="简介">
              {{ picture.introduction ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="分类">
              {{ picture.category ?? '默认' }}
            </a-descriptions-item>
            <a-descriptions-item label="标签">
              <a-tag v-for="tag in picture.tags" :key="tag">
                {{ tag }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="格式">
              {{ picture.picFormat ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽度">
              {{ picture.picWidth ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="高度">
              {{ picture.picHeight ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽高比">
              {{ picture.picScale ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="大小">
              {{ formatSize(picture.picSize) }}
            </a-descriptions-item>
            <a-descriptions-item label="下载次数（按用户/日去重）">
              {{ picture.downloadCount ?? 0 }}
            </a-descriptions-item>
            <a-descriptions-item label="收藏 / 点赞">
              {{ formatCount(picture.favoriteCount) }} / {{ formatCount(picture.likeCount) }}
            </a-descriptions-item>
          </a-descriptions>
          <a-space wrap>
            <!-- 收藏 / 点赞：乐观更新 + 未登录跳登录并带回跳地址 -->
            <InteractionButtons
              v-if="picture.id"
              :picture-id="String(picture.id)"
              :favorite-count="picture.favoriteCount"
              :like-count="picture.likeCount"
            />
            <a-button v-if="canEdit" type="default" @click="doEdit">
              编辑
              <template #icon>
                <EditOutlined />
              </template>
            </a-button>
            <a-popconfirm
              v-if="canEdit"
              title="确定删除这张图片吗？"
              ok-text="删除"
              cancel-text="取消"
              @confirm="doDelete"
            >
              <a-button danger>
                删除
                <template #icon>
                  <DeleteOutlined />
                </template>
              </a-button>
            </a-popconfirm>
            <a-button v-if="picture.reviewStatus === 1" @click="doShare">
              分享
              <template #icon>
                <ShareAltOutlined />
              </template>
            </a-button>
            <a-button type="primary" @click="doDownload">
              免费下载
              <template #icon>
                <DownloadOutlined />
              </template>
            </a-button>
          </a-space>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
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
import { downloadPicture, formatSize } from '@/utils'
import { formatCount } from '@/utils/interaction'
import InteractionButtons from '@/components/InteractionButtons.vue'

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

// 获取图片详情
const fetchPictureDetail = async () => {
  loading.value = true
  try {
    const res = await getPictureVoById({
      id: props.id,
    })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
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

// 分享：复制当前详情页链接
const doShare = async () => {
  const shareUrl = window.location.href
  try {
    await navigator.clipboard.writeText(shareUrl)
    message.success('链接已复制，可以分享给他人了')
  } catch {
    message.info('复制失败，请手动复制浏览器地址栏链接：' + shareUrl)
  }
}

// 处理下载：直接走后端流式代理，后端在文件完整返回后按「用户/IP + 图片 + 天」去重计数
const doDownload = () => {
  downloadPicture(picture.value.id)
}

onMounted(() => {
  fetchPictureDetail()
})
</script>

<style scoped>
.author-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: inherit;
}

.author-link:hover {
  color: #1677ff;
}
</style>
