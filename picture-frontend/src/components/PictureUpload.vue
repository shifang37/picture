<template>
  <div class="picture-upload">
    <a-upload
      list-type="picture-card"
      :show-upload-list="false"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
      :disabled="loading"
      accept=".jpg,.jpeg,.png,.webp,.heic,.heif"
    >
      <img v-if="picture?.url" :src="picture?.url" alt="图片预览" />
      <div v-else>
        <loading-outlined v-if="loading"></loading-outlined>
        <plus-outlined v-else></plus-outlined>
        <div class="ant-upload-text">点击或拖拽上传图片</div>
      </div>
    </a-upload>
    <div v-if="loading || percent > 0" class="upload-status">
      <a-progress
        :percent="percent"
        :status="percent >= 100 ? 'success' : 'active'"
        size="small"
        :show-info="false"
      />
      <span class="status-text">{{ statusText }}</span>
    </div>
    <div class="upload-tip">
      支持 jpg / jpeg / png / webp / heic，单张不超过 10MB；上传前会自动压缩到 2MB 以内。
      普通用户上传后需管理员审核。
    </div>
  </div>
</template>
<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { PlusOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { uploadPicture } from '@/api/pictureExtra'
import { getMyQuota, type UserQuota } from '@/api/quotaExtra'
import { describeCompression } from '@/utils/image'
import { checkQuotaForUpload, summarizeQuota } from '@/utils/quota'
import { prepareImageForUpload, validateImageFile } from '@/utils/uploadPrepare'

interface Props {
  picture?: API.PictureVO
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

const loading = ref<boolean>(false)
const percent = ref<number>(0)
const statusText = ref<string>('')
const quota = ref<UserQuota | null>(null)

/**
 * 读取配额：上传前预检查，上传成功后刷新用量
 */
const loadQuota = async () => {
  try {
    const res = await getMyQuota()
    if (res.data.code === 0) {
      quota.value = res.data.data ?? null
    }
  } catch {
    // 配额读取失败不阻断上传，后端仍会做最终校验
  }
}

onMounted(loadQuota)

/**
 * 上传前校验：格式、大小（与后端 app.upload.max-file-size-bytes 保持一致）
 */
const beforeUpload = (file: File) => {
  const error = validateImageFile(file)
  if (error) {
    message.error(error)
    return false
  }
  return true
}

/**
 * 上传：HEIC 转换 → 压缩 → 带进度的 multipart 上传
 * @param file 文件
 */
const handleUpload = async ({ file, onSuccess: uploadSuccess, onError }: any) => {
  loading.value = true
  percent.value = 0
  statusText.value = '处理中...'
  try {
    // 手机直出照片可能超过服务端上限或不是浏览器可解码的格式，先统一处理
    const prepared = await prepareImageForUpload(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1920,
      onStage: (stage) => {
        statusText.value = stage === 'converting' ? 'HEIC 转换中...' : '压缩中...'
      },
    })
    const compressionTip = prepared.compression
      ? `已压缩 ${describeCompression(prepared.compression)}`
      : ''
    // 客户端预检查：提前给出“已用/上限”，避免白传一次再被后端拒绝
    const quotaError = checkQuotaForUpload(summarizeQuota(quota.value), prepared.file.size)
    if (quotaError) {
      message.error(quotaError)
      onError?.(new Error(quotaError))
      return
    }
    statusText.value = compressionTip ? `${compressionTip}，上传中...` : '上传中...'
    const res = await uploadPicture(
      prepared.file,
      props.picture?.id ? { id: props.picture.id } : undefined,
      {
        onUploadProgress: (value) => {
          percent.value = value
        },
      },
    )
    if (res.data.code === 0 && res.data.data) {
      percent.value = 100
      const newPicture = res.data.data
      if (newPicture.duplicated) {
        message.info('已存在相同图片，已为你复用，不重复占用空间')
      } else if (newPicture.reviewStatus === 1) {
        message.success(compressionTip ? `图片上传成功（${compressionTip}）` : '图片上传成功')
      } else {
        message.success(
          compressionTip
            ? `图片上传成功（${compressionTip}），等待管理员审核`
            : '图片上传成功，等待管理员审核',
        )
      }
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(newPicture)
      uploadSuccess?.(newPicture)
      // 用量已经变化，刷新个人配额（失败静默）
      void loadQuota()
    } else {
      const errorMessage = res.data.message || '未知错误'
      message.error('图片上传失败，' + errorMessage)
      onError?.(new Error(errorMessage))
    }
  } catch (error: any) {
    message.error(error?.message || '图片上传失败，系统错误')
    onError?.(error)
  } finally {
    statusText.value = ''
    loading.value = false
  }
}
</script>
<style scoped>
.picture-upload :deep(.ant-upload) {
  width: 100% !important;
  height: 100% !important;
  min-height: 152px;
  min-width: 152px;
}

.picture-upload img {
  max-width: 100%;
  max-height: 480px;
}

.upload-status {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.status-text {
  color: #1677ff;
  font-size: 12px;
}

.upload-tip {
  margin-top: 8px;
  color: #999;
  font-size: 12px;
  line-height: 1.5;
}

.ant-upload-select-picture-card i {
  font-size: 32px;
  color: #999;
}

.ant-upload-select-picture-card .ant-upload-text {
  margin-top: 8px;
  color: #666;
}
</style>
