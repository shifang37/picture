<template>
  <div id="userChangePasswordPage">
    <a-card title="修改初始密码">
      <a-alert
        type="warning"
        show-icon
        message="首次登录或管理员重置密码后，需要先修改初始密码才能继续使用系统。"
        style="margin-bottom: 20px"
      />
      <a-form :model="formState" :rules="rules" layout="vertical" @finish="handleSubmit">
        <a-form-item label="当前密码" name="oldPassword">
          <a-input-password
            v-model:value="formState.oldPassword"
            placeholder="请输入当前密码（初始密码）"
            autocomplete="current-password"
          />
        </a-form-item>
        <a-form-item label="新密码" name="newPassword">
          <a-input-password
            v-model:value="formState.newPassword"
            :placeholder="`请输入新密码（${PASSWORD_HINT}）`"
            :maxlength="PASSWORD_MAX_LENGTH"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item label="确认新密码" name="confirmPassword">
          <a-input-password
            v-model:value="formState.confirmPassword"
            placeholder="请再次输入新密码"
            :maxlength="PASSWORD_MAX_LENGTH"
            autocomplete="new-password"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" style="width: 100%" :loading="loading">
            修改密码并进入系统
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { updatePassword } from '@/api/userExtra'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { PASSWORD_HINT, PASSWORD_MAX_LENGTH, passwordStrengthRule } from '@/utils/password'
import { message, type FormProps } from 'ant-design-vue'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const loading = ref(false)

const formState = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const validateConfirmPassword = (_rule: any, value: string) => {
  if (value && value !== formState.newPassword) {
    return Promise.reject('两次输入的新密码不一致')
  }
  return Promise.resolve()
}

const rules: FormProps['rules'] = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { ...passwordStrengthRule, trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
}

const handleSubmit = async () => {
  loading.value = true
  try {
    const res = await updatePassword({
      oldPassword: formState.oldPassword,
      newPassword: formState.newPassword,
    })
    if (res.data.code === 0) {
      // 服务端已清除强制改密标记，刷新登录用户信息后回到首页
      await loginUserStore.fetchLoginUser()
      message.success('密码修改成功')
      router.replace('/')
    } else {
      message.error('密码修改失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('密码修改失败：' + (e.message || '系统错误'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
#userChangePasswordPage {
  max-width: 420px;
  margin: 0 auto;
}
</style>
