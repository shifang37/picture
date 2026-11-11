<template>
  <div class="picture-upload">
    <a-upload
      list-type="picture-card"
      :show-upload-list="false"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
      :disabled="loading"
      accept=".jpg,.jpeg,.png,.webp"
    >
      <img v-if="picture?.url" :src="picture?.url" alt="图片预览" />
      <div v-else>
        <loading-outlined v-if="loading"></loading-outlined>
        <plus-outlined v-else></plus-outlined>
        <div class="ant-upload-text">点击或拖拽上传图片</div>
      </div>
    </a-upload>
    <div class="upload-tip">
      支持 jpg / jpeg / png / webp，单张不超过 2MB；普通用户上传后需管理员审核。
    </div>
  </div>
</template>
<script lang="ts" setup>
import { ref } from 'vue'
import { PlusOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { uploadPicture } from '@/api/pictureExtra'

interface Props {
  picture?: API.PictureVO
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

const loading = ref<boolean>(false)

// 与后端 FileManager 的校验规则保持一致
const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp']
const MAX_SIZE_MB = 2

/**
 * 上传前校验：格式、大小
 */
const beforeUpload = (file: File) => {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  // 部分浏览器对某些图片拿不到 MIME 类型，此时只按后缀判断，最终以后端文件头校验为准
  const typeAllowed = !file.type || ALLOWED_MIME_TYPES.includes(file.type)
  if (!ALLOWED_EXTENSIONS.includes(extension) || !typeAllowed) {
    message.error('仅支持 jpg、jpeg、png、webp 格式的图片')
    return false
  }
  if (!file.size) {
    message.error('不能上传空文件')
    return false
  }
  if (file.size > MAX_SIZE_MB * 1024 * 1024) {
    message.error(`不能上传超过 ${MAX_SIZE_MB}M 的图片`)
    return false
  }
  return true
}

/**
 * 上传
 * @param file 文件
 */
const handleUpload = async ({ file, onSuccess: uploadSuccess, onError }: any) => {
  loading.value = true
  try {
    const res = await uploadPicture(file, props.picture?.id ? { id: props.picture.id } : undefined)
    if (res.data.code === 0 && res.data.data) {
      const newPicture = res.data.data
      if (newPicture.reviewStatus === 1) {
        message.success('图片上传成功')
      } else {
        message.success('图片上传成功，等待管理员审核')
      }
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(newPicture)
      uploadSuccess?.(newPicture)
    } else {
      const errorMessage = res.data.message || '未知错误'
      message.error('图片上传失败，' + errorMessage)
      onError?.(new Error(errorMessage))
    }
  } catch (error: any) {
    message.error('图片上传失败，' + (error.message || '系统错误'))
    console.error('上传错误:', error)
    onError?.(error)
  } finally {
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
