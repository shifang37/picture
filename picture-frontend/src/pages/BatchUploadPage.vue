<template>
  <div id="batchUploadPage">
    <h2>批量上传</h2>
    <a-alert
      type="info"
      show-icon
      message="批量上传会以文件名作为图片名称；普通用户上传后统一进入待审核，管理员上传自动通过。建议单次不超过 20 张。"
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
        支持 jpg / jpeg / png / webp，单张不超过 2MB；上传过程中请勿关闭页面。
      </p>
    </a-upload-dragger>

    <a-space style="margin: 16px 0">
      <a-button type="primary" @click="goMyPictures">查看我的图片</a-button>
      <span v-if="uploadingCount > 0" class="uploading-text">
        正在上传 {{ uploadingCount }} 张...
      </span>
    </a-space>

    <a-table
      :columns="columns"
      :data-source="results"
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
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { InboxOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { uploadPicture } from '@/api/pictureExtra'

interface BatchUploadItem {
  uid: string
  name: string
  status: 'uploading' | 'success' | 'error'
  message?: string
  reviewStatus?: number
}

const router = useRouter()
const results = ref<BatchUploadItem[]>([])
const uploadingCount = ref(0)

const columns = [
  { title: '文件名', dataIndex: 'name', key: 'name' },
  { title: '状态', key: 'status', width: 120 },
  { title: '说明', key: 'message' },
]

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp']
const MAX_SIZE_MB = 2

const statusText = (status: BatchUploadItem['status']) => {
  return { uploading: '上传中', success: '成功', error: '失败' }[status]
}

const statusColor = (status: BatchUploadItem['status']) => {
  return { uploading: 'blue', success: 'green', error: 'red' }[status]
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
  if (file.size > MAX_SIZE_MB * 1024 * 1024) {
    message.error(`${file.name}：不能超过 ${MAX_SIZE_MB}M`)
    return false
  }
  return true
}

const handleUpload = async ({ file, onSuccess, onError }: any) => {
  const item = reactive<BatchUploadItem>({
    uid: String(file.uid ?? `${file.name}-${Date.now()}`),
    name: file.name,
    status: 'uploading',
  })
  results.value.unshift(item)
  uploadingCount.value++
  try {
    const res = await uploadPicture(file as File)
    if (res.data.code === 0 && res.data.data) {
      item.status = 'success'
      item.reviewStatus = res.data.data.reviewStatus
      item.message = res.data.data.reviewStatus === 1 ? '已自动通过审核' : '已提交，等待管理员审核'
      onSuccess?.(res.data.data)
    } else {
      item.status = 'error'
      item.message = res.data.message || '上传失败'
      onError?.(new Error(item.message))
    }
  } catch (e: any) {
    item.status = 'error'
    item.message = e.message || '系统错误'
    onError?.(e)
  } finally {
    uploadingCount.value--
  }
}

const goMyPictures = () => {
  router.push('/my_pictures')
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
</style>
