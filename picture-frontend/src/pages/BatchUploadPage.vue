<template>
  <div id="batchUploadPage">
    <h2>批量上传</h2>
    <a-alert
      type="info"
      show-icon
      message="批量上传会以文件名作为图片名称；HEIC 会自动转成 JPG，超过 2MB 的图片会先压缩。普通用户上传后统一进入待审核，管理员上传自动通过。最多同时上传 3 张，失败项可以重试。"
      style="margin-bottom: 16px"
    />

    <a-upload-dragger
      multiple
      :show-upload-list="false"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
      accept=".jpg,.jpeg,.png,.webp,.heic,.heif"
    >
      <p class="ant-upload-drag-icon">
        <inbox-outlined />
      </p>
      <p class="ant-upload-text">点击或拖拽多张图片到此处</p>
      <p class="ant-upload-hint">
        支持 jpg / jpeg / png / webp / heic，单张不超过 10MB；超过的目标体积会压缩到 2MB
        以内，超限文件会显示为失败并可以重试。
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
        <template v-else-if="column.key === 'progress'">
          <a-progress
            v-if="record.status === 'uploading'"
            :percent="record.progress"
            size="small"
            style="width: 120px"
          />
          <span v-else-if="record.status === 'success'">100%</span>
          <span v-else class="action-placeholder">-</span>
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
import { describeCompression } from '@/utils/image'
import { prepareImageForUpload, validateImageFile } from '@/utils/uploadPrepare'
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
  { title: '进度', key: 'progress', width: 150 },
  { title: '说明', key: 'message' },
  { title: '操作', key: 'action', width: 90 },
]

// 并发与重试逻辑收敛到 batchUpload.ts，页面只负责展示
const scheduler = createBatchUploadScheduler({
  upload: async (file: File, reporter) => {
    const prepared = await prepareImageForUpload(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1920,
      onStage: (stage) => {
        reporter.status(stage === 'converting' ? 'HEIC 转换中...' : '压缩中...')
      },
    })
    const compressionTip = prepared.compression
      ? `已压缩 ${describeCompression(prepared.compression)}`
      : ''
    if (compressionTip) {
      reporter.status(`${compressionTip}，上传中...`)
    }
    const res = await uploadPicture(prepared.file, undefined, {
      onUploadProgress: reporter.progress,
    })
    if (res.data.code === 0 && res.data.data) {
      const reviewStatus = res.data.data.reviewStatus
      const duplicated = Boolean(res.data.data.duplicated)
      return {
        pictureId: res.data.data.id,
        reviewStatus,
        duplicated,
        message: duplicated
          ? '已存在相同图片，已复用'
          : [compressionTip, reviewStatus === 1 ? '已自动通过审核' : '已提交，等待管理员审核']
              .filter(Boolean)
              .join('，'),
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
  const error = validateImageFile(file)
  if (error) {
    message.error(`${file.name}：${error}`)
    return false
  }
  // 大小已在上面的校验里拦住；其余失败项会进入列表并支持重试
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
