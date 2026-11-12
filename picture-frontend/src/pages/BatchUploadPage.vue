<template>
  <div id="batchUploadPage">
    <h2>批量上传</h2>
    <a-alert
      type="info"
      show-icon
      message="批量上传会以文件名作为图片名称；普通用户上传后统一进入待审核，管理员上传自动通过。最多同时上传 3 张，失败项可以重试。"
      style="margin-bottom: 16px"
    />

    <a-upload-dragger
      multiple
      :show-upload-list="false"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
      accept=".jpg,.jpeg,.png,.webp"
    >
      <p class="ant-upload-drag-icon">
        <inbox-outlined />
      </p>
      <p class="ant-upload-text">点击或拖拽多张图片到此处</p>
      <p class="ant-upload-hint">
        支持 jpg / jpeg / png / webp，单张不超过 2MB；超过限制的文件会显示为失败并可以重试。
      </p>
    </a-upload-dragger>

    <a-space style="margin: 16px 0" wrap>
      <a-button type="primary" @click="goMyPictures">查看我的图片</a-button>
      <a-button :disabled="!hasRecords" @click="clearRecords">清空记录</a-button>
      <a-button :disabled="failedCount === 0" @click="retryAllFailed">
        重试全部失败（{{ failedCount }}）
      </a-button>
      <a-button :disabled="!successPictureIds.length" @click="batchEditOpen = true">
        批量设置信息（{{ successPictureIds.length }}）
      </a-button>
      <span v-if="uploadingCount > 0" class="uploading-text">
        正在上传 {{ uploadingCount }} 张...
      </span>
    </a-space>

    <a-table
      :columns="columns"
      :data-source="scheduler.items"
      :pagination="false"
      row-key="uid"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'message'">
          {{ record.message || '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button
            v-if="record.status === 'error'"
            type="link"
            size="small"
            @click="scheduler.retry(record.uid)"
          >
            重试
          </a-button>
          <span v-else class="action-placeholder">-</span>
        </template>
      </template>
    </a-table>

    <BatchEditModal
      v-model:open="batchEditOpen"
      :ids="successPictureIds"
      @success="handleBatchEdited"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { InboxOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { uploadPicture } from '@/api/pictureExtra'
import BatchEditModal from '@/components/BatchEditModal.vue'
import {
  createBatchUploadScheduler,
  type BatchUploadItem,
  type BatchUploadStatus,
} from '@/utils/batchUpload'

const router = useRouter()

const batchEditOpen = ref(false)

const columns = [
  { title: '文件名', dataIndex: 'name', key: 'name' },
  { title: '状态', key: 'status', width: 110 },
  { title: '说明', key: 'message' },
  { title: '操作', key: 'action', width: 90 },
]

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp']

// 并发与重试逻辑收敛到 batchUpload.ts，页面只负责展示
const scheduler = createBatchUploadScheduler({
  upload: async (file: File) => {
    const res = await uploadPicture(file)
    if (res.data.code === 0 && res.data.data) {
      const reviewStatus = res.data.data.reviewStatus
      return {
        pictureId: res.data.data.id,
        reviewStatus,
        message: reviewStatus === 1 ? '已自动通过审核' : '已提交，等待管理员审核',
      }
    }
    throw new Error(res.data.message || '上传失败')
  },
})

const uploadingCount = computed(
  () => scheduler.items.filter((item) => item.status === 'uploading').length,
)
const failedCount = computed(() => scheduler.items.filter((item) => item.status === 'error').length)
const hasRecords = computed(() => scheduler.items.length > 0)
// 上传成功后才有图片 id，可用于一次性批量设置分类/标签/简介
const successPictureIds = computed(() =>
  scheduler.items
    .filter((item) => item.status === 'success' && item.pictureId)
    .map((item) => String(item.pictureId)),
)

const statusText = (status: BatchUploadStatus) => {
  return { waiting: '排队中', uploading: '上传中', success: '成功', error: '失败' }[status]
}

const statusColor = (status: BatchUploadStatus) => {
  return { waiting: 'default', uploading: 'blue', success: 'green', error: 'red' }[status]
}

const beforeUpload = (file: File) => {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  const typeAllowed = !file.type || ALLOWED_MIME_TYPES.includes(file.type)
  if (!ALLOWED_EXTENSIONS.includes(extension) || !typeAllowed) {
    message.error(`${file.name}：仅支持 jpg、jpeg、png、webp 格式`)
    return false
  }
  if (!file.size) {
    message.error(`${file.name}：不能上传空文件`)
    return false
  }
  // 大小交给后端校验：失败项会进入列表并支持重试
  return true
}

const handleUpload = ({ file, onSuccess }: any) => {
  scheduler.addFiles([file as File])
  // 上传列表不展示，这里立即结束 antd 的内部状态，实际进度以表格为准
  onSuccess?.({})
}

const clearRecords = () => {
  scheduler.clear()
}

const retryAllFailed = () => {
  for (const item of scheduler.items.filter(
    (current: BatchUploadItem) => current.status === 'error',
  )) {
    scheduler.retry(item.uid)
  }
}

const goMyPictures = () => {
  router.push('/my_pictures')
}

const handleBatchEdited = () => {
  // 批量编辑的提示由弹窗给出，这里不再重复提示
  batchEditOpen.value = false
}
</script>

<style scoped>
#batchUploadPage {
  max-width: 900px;
  margin: 0 auto;
}

.uploading-text {
  color: #1677ff;
}

.action-placeholder {
  color: #bbb;
}
</style>
