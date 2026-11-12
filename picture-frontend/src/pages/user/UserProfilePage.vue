<template>
  <div id="userProfilePage">
    <h2 class="title">个人中心</h2>

    <a-card class="section" title="账号信息">
      <a-descriptions :column="2" bordered size="middle">
        <a-descriptions-item label="账号">
          {{ loginUser.userAccount || '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="角色">
          <a-tag :color="loginUser.userRole === 'admin' ? 'green' : 'blue'">
            {{ loginUser.userRole === 'admin' ? '管理员' : '普通用户' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="昵称">
          {{ loginUser.userName || '-' }}
        </a-descriptions-item>
        <a-descriptions-item label="注册时间">
          {{ formatTime(loginUser.createTime) }}
        </a-descriptions-item>
      </a-descriptions>
    </a-card>

    <a-card class="section" title="存储空间">
      <a-spin :spinning="quotaLoading">
        <template v-if="quota">
          <div class="quota-row">
            <span class="quota-label">存储空间</span>
            <span class="quota-value">{{ describeStorageQuota(quotaSummary) }}</span>
          </div>
          <a-progress
            :percent="quotaSummary.percent"
            :status="quotaSummary.percent >= 100 ? 'exception' : 'normal'"
          />
          <div class="quota-row">
            <span class="quota-label">图片数量</span>
            <span class="quota-value">{{ describePictureQuota(quotaSummary) }}</span>
          </div>
          <a-progress
            :percent="picturePercent"
            :status="picturePercent >= 100 ? 'exception' : 'normal'"
          />
          <a-alert
            v-if="quotaSummary.exempt"
            type="info"
            show-icon
            message="当前账号不受存储配额限制。"
            style="margin-bottom: 12px"
          />
          <a-alert
            v-else-if="quotaSummary.exceeded"
            type="warning"
            show-icon
            message="配额已用尽，删除图片会立即释放额度；如需更多空间请联系管理员。"
            style="margin-bottom: 12px"
          />
          <a-button size="small" :loading="quotaLoading" @click="loadQuota">刷新用量</a-button>
        </template>
        <a-empty v-else-if="!quotaLoading" description="配额信息加载失败" />
      </a-spin>
    </a-card>

    <a-card class="section" title="编辑资料">
      <a-form
        :model="profileForm"
        :rules="profileRules"
        layout="vertical"
        @finish="handleUpdateProfile"
      >
        <a-form-item label="昵称" name="userName">
          <a-input
            v-model:value="profileForm.userName"
            placeholder="请输入昵称"
            :maxlength="256"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="头像地址" name="userAvatar">
          <a-input
            v-model:value="profileForm.userAvatar"
            placeholder="请输入头像图片地址"
            :maxlength="1024"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="简介" name="userProfile">
          <a-textarea
            v-model:value="profileForm.userProfile"
            placeholder="介绍一下自己吧"
            :rows="3"
            :maxlength="512"
            show-count
            allow-clear
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="profileLoading">保存资料</a-button>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card class="section" title="修改密码">
      <a-form
        :model="passwordForm"
        :rules="passwordRules"
        layout="vertical"
        @finish="handleUpdatePassword"
      >
        <a-form-item label="原密码" name="oldPassword">
          <a-input-password
            v-model:value="passwordForm.oldPassword"
            placeholder="请输入原密码"
            autocomplete="current-password"
          />
        </a-form-item>
        <a-form-item label="新密码" name="newPassword">
          <a-input-password
            v-model:value="passwordForm.newPassword"
            :placeholder="`请输入新密码（${PASSWORD_HINT}）`"
            :maxlength="PASSWORD_MAX_LENGTH"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item label="确认新密码" name="confirmPassword">
          <a-input-password
            v-model:value="passwordForm.confirmPassword"
            placeholder="请再次输入新密码"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-alert
          type="info"
          show-icon
          message="修改密码后，其他设备上的登录会话会失效；当前页面保持登录状态。"
          style="margin-bottom: 16px"
        />
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="passwordLoading">
            修改密码
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, type FormProps } from 'ant-design-vue'
import dayjs from 'dayjs'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { getMyQuota, type UserQuota } from '@/api/quotaExtra'
import { updatePassword, updateProfile } from '@/api/userExtra'
import { PASSWORD_HINT, PASSWORD_MAX_LENGTH, passwordStrengthRule } from '@/utils/password'
import { describePictureQuota, describeStorageQuota, summarizeQuota } from '@/utils/quota'
import { percentOf } from '@/utils/size'

const loginUserStore = useLoginUserStore()

const loginUser = computed(() => loginUserStore.loginUser)

const profileLoading = ref(false)
const passwordLoading = ref(false)
const quotaLoading = ref(false)
const quota = ref<UserQuota | null>(null)

const quotaSummary = computed(() => summarizeQuota(quota.value))

const picturePercent = computed(() =>
  percentOf(quotaSummary.value.usedPictures, quotaSummary.value.quotaPictures),
)

/**
 * 加载配额：失败只提示，不影响页面其余功能
 */
const loadQuota = async () => {
  quotaLoading.value = true
  try {
    const res = await getMyQuota()
    if (res.data.code === 0 && res.data.data) {
      quota.value = res.data.data
    } else {
      message.error('获取配额失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('获取配额失败：' + (e.message || '系统错误'))
  } finally {
    quotaLoading.value = false
  }
}

const profileForm = reactive({
  userName: '',
  userAvatar: '',
  userProfile: '',
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const profileRules: FormProps['rules'] = {
  userName: [{ max: 256, message: '昵称不能超过 256 个字符', trigger: 'blur' }],
  userAvatar: [{ max: 1024, message: '头像地址不能超过 1024 个字符', trigger: 'blur' }],
  userProfile: [{ max: 512, message: '简介不能超过 512 个字符', trigger: 'blur' }],
}

const validateConfirmPassword = (_rule: any, value: string) => {
  if (value && value !== passwordForm.newPassword) {
    return Promise.reject('两次输入的新密码不一致')
  }
  return Promise.resolve()
}

const passwordRules: FormProps['rules'] = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { ...passwordStrengthRule, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
}

const formatTime = (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-')

// 把登录用户信息同步到表单
const fillProfileForm = () => {
  profileForm.userName = loginUser.value.userName ?? ''
  profileForm.userAvatar = loginUser.value.userAvatar ?? ''
  profileForm.userProfile = loginUser.value.userProfile ?? ''
}

const handleUpdateProfile = async () => {
  profileLoading.value = true
  try {
    const res = await updateProfile({
      userName: profileForm.userName,
      userAvatar: profileForm.userAvatar,
      userProfile: profileForm.userProfile,
    })
    if (res.data.code === 0) {
      message.success('资料更新成功')
      // 刷新全局登录用户信息，头部昵称/头像同步更新
      await loginUserStore.fetchLoginUser()
      fillProfileForm()
    } else {
      message.error('资料更新失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('资料更新失败：' + (e.message || '系统错误'))
  } finally {
    profileLoading.value = false
  }
}

const handleUpdatePassword = async () => {
  passwordLoading.value = true
  try {
    const res = await updatePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
    })
    if (res.data.code === 0) {
      message.success('密码修改成功，请使用新密码登录')
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.confirmPassword = ''
    } else {
      message.error('密码修改失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('密码修改失败：' + (e.message || '系统错误'))
  } finally {
    passwordLoading.value = false
  }
}

onMounted(async () => {
  if (!loginUser.value.id) {
    await loginUserStore.fetchLoginUser()
  }
  fillProfileForm()
  await loadQuota()
})
</script>

<style scoped>
#userProfilePage {
  max-width: 720px;
  margin: 0 auto;
}

.title {
  margin-bottom: 16px;
}

.section {
  margin-bottom: 16px;
}

.quota-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.quota-label {
  color: #666;
}

.quota-value {
  color: #111;
  font-variant-numeric: tabular-nums;
}
</style>
