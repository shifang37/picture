<template>
  <div id="userRegisterPage">
    <h2 class="title">鱼皮云图库 - 用户注册</h2>
    <div class="desc">企业级智能协同云图库</div>
    <a-form
      :model="formState"
      name="basic"
      label-align="left"
      autocomplete="off"
      @finish="handleSubmit"
    >
      <a-form-item
        name="userAccount"
        :rules="[
          { required: true, message: '请输入账号' },
          { min: 4, max: 256, message: '账号长度需在 4 到 256 个字符之间' },
        ]"
      >
        <a-input
          v-model:value="formState.userAccount"
          placeholder="请输入账号（4-256 个字符）"
          :maxlength="256"
        />
      </a-form-item>
      <a-form-item name="userPassword" :rules="passwordRules">
        <a-input-password
          v-model:value="formState.userPassword"
          :placeholder="`请输入密码（${PASSWORD_HINT}）`"
          :maxlength="PASSWORD_MAX_LENGTH"
        />
      </a-form-item>
      <a-form-item
        name="checkPassword"
        :rules="[
          { required: true, message: '请输入确认密码' },
          { validator: validateConfirmPassword, trigger: 'blur' },
        ]"
      >
        <a-input-password
          v-model:value="formState.checkPassword"
          placeholder="请再次输入密码"
          :maxlength="PASSWORD_MAX_LENGTH"
        />
      </a-form-item>
      <a-form-item
        name="captchaCode"
        :rules="[
          { required: true, message: '请输入验证码' },
          { len: 4, message: '请输入 4 位验证码' },
        ]"
      >
        <div class="captcha-row">
          <a-input
            v-model:value="formState.captchaCode"
            placeholder="请输入右侧验证码"
            :maxlength="4"
            autocomplete="off"
          />
          <a-spin :spinning="captchaLoading">
            <img
              v-if="captchaImage"
              class="captcha-image"
              :src="captchaImage"
              alt="图形验证码"
              title="看不清？点击刷新"
              @click="refreshCaptcha"
            />
            <button v-else type="button" class="captcha-refresh" @click="refreshCaptcha">
              获取验证码
            </button>
          </a-spin>
        </div>
      </a-form-item>
      <div class="tips">
        已有账号？
        <RouterLink to="/user/login">去登录</RouterLink>
      </div>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%" :loading="loading">
          注册
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { userRegister } from '@/api/userController'
import { getRegisterCaptcha } from '@/api/userExtra'
import { PASSWORD_HINT, PASSWORD_MAX_LENGTH, passwordStrengthRule } from '@/utils/password'
import { message } from 'ant-design-vue'
import type { RuleObject } from 'ant-design-vue/es/form/interface'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
  captchaCode: '',
})
const router = useRouter()
const loading = ref(false)
const captchaLoading = ref(false)
const captchaId = ref('')
const captchaImage = ref('')

const passwordRules: RuleObject[] = [
  { required: true, message: '请输入密码' },
  { ...passwordStrengthRule, trigger: 'blur' },
]

// 与后端 DTO 的密码上限保持一致
const validateConfirmPassword = (_rule: any, value: string) => {
  if (value && value !== formState.userPassword) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

/**
 * 获取图形验证码。后端一次性使用，任何失败提交后都需要重新获取。
 */
const refreshCaptcha = async () => {
  captchaLoading.value = true
  try {
    const res = await getRegisterCaptcha()
    if (res.data.code === 0 && res.data.data) {
      captchaId.value = res.data.data.captchaId || ''
      captchaImage.value = res.data.data.image || ''
      formState.captchaCode = ''
    } else {
      message.error('验证码获取失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('验证码获取失败：' + (e.message || '系统错误'))
  } finally {
    captchaLoading.value = false
  }
}

onMounted(refreshCaptcha)

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  if (!captchaId.value) {
    message.error('验证码已失效，请点击图片刷新')
    await refreshCaptcha()
    return
  }
  // 判断两次输入的密码是否一致
  if (formState.userPassword !== formState.checkPassword) {
    message.error('二次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    const res = await userRegister({ ...values, captchaId: captchaId.value })
    // 注册成功，跳转到登录页面
    if (res.data.code === 0 && res.data.data) {
      message.success('注册成功，请登录')
      router.push({
        path: '/user/login',
        replace: true,
      })
    } else {
      message.error('注册失败：' + (res.data.message || '未知错误'))
      // 验证码无论对错都已被消费，失败后立即换一张，避免用户重复提交同一验证码
      await refreshCaptcha()
    }
  } catch (e: any) {
    message.error('注册失败：' + (e.message || '系统错误'))
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
#userRegisterPage {
  max-width: 360px;
  margin: 0 auto;
}

.title {
  text-align: center;
  margin-bottom: 16px;
}

.desc {
  text-align: center;
  color: #bbb;
  margin-bottom: 16px;
}

.captcha-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.captcha-image {
  display: block;
  width: 120px;
  height: 32px;
  cursor: pointer;
  border-radius: 4px;
  border: 1px solid #d9d9d9;
}

.captcha-refresh {
  height: 32px;
  padding: 0 8px;
  cursor: pointer;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
}

.tips {
  margin-bottom: 16px;
  color: #bbb;
  font-size: 13px;
  text-align: right;
}
</style>
