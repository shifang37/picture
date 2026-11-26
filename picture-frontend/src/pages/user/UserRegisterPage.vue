<template>
  <div id="userRegisterPage" class="auth-page">
    <div class="auth-card">
      <aside class="auth-aside">
        <div class="aside-brand">
          <span class="aside-brand-mark">shifang37</span>
          <span class="aside-brand-sub">云图库</span>
        </div>
        <h2 class="aside-title">创建你的私人图片工作台</h2>
        <p class="aside-desc">
          注册后即可上传图片、建立专辑、用标签和分类整理素材，随时分享给朋友。
        </p>
        <ul class="aside-points">
          <li>独立存储配额</li>
          <li>专辑与标签整理</li>
          <li>审核与回收站管理</li>
        </ul>
        <p class="aside-foot">shifang37 · 图片素材管理与分享</p>
      </aside>
      <section class="auth-main">
        <h2 class="title">注册账号</h2>
        <p class="desc">几秒钟创建账号，开始整理你的图片</p>
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
              placeholder="账号（4-256 个字符）"
              :maxlength="256"
            />
          </a-form-item>
          <a-form-item name="userPassword" :rules="passwordRules">
            <a-input-password
              v-model:value="formState.userPassword"
              :placeholder="`密码（${PASSWORD_HINT}）`"
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
              placeholder="再次输入密码"
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
                placeholder="验证码"
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
            <a-button type="primary" html-type="submit" class="submit-btn" :loading="loading">
              注册
            </a-button>
          </a-form-item>
        </a-form>
      </section>
    </div>
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
.auth-page {
  display: grid;
  min-height: calc(100vh - 220px);
  padding: 24px 0;
  place-items: center;
}

.auth-card {
  display: grid;
  grid-template-columns: 1.04fr 1fr;
  width: min(940px, 100%);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
}

.auth-aside {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 46px 42px;
  overflow: hidden;
  color: #fff;
  background:
    radial-gradient(420px 300px at 10% 0%, rgba(37, 99, 235, 0.35), transparent 65%),
    radial-gradient(360px 280px at 95% 100%, rgba(14, 165, 233, 0.25), transparent 65%),
    linear-gradient(155deg, #18181b 0%, #1e2433 55%, #111827 100%);
}

.auth-aside::after {
  position: absolute;
  top: -80px;
  right: -60px;
  width: 240px;
  height: 240px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.14), transparent 65%);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.aside-brand {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 40px;
}

.aside-brand-mark {
  font-weight: 700;
  font-size: 18px;
  letter-spacing: -0.02em;
}

.aside-brand-sub {
  color: rgba(255, 255, 255, 0.66);
  font-size: 11px;
  letter-spacing: 0.26em;
}

.aside-title {
  margin: 0 0 14px;
  font-weight: 700;
  font-size: 26px;
  line-height: 1.38;
  letter-spacing: -0.02em;
}

.aside-desc {
  margin: 0;
  color: rgba(255, 255, 255, 0.76);
  font-size: 13.5px;
  line-height: 1.85;
}

.aside-points {
  display: grid;
  gap: 10px;
  margin: 30px 0 0;
  padding: 0;
  list-style: none;
}

.aside-points li {
  display: flex;
  gap: 10px;
  align-items: center;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
}

.aside-points li::before {
  width: 16px;
  height: 16px;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  content: '✓';
  background: rgba(255, 255, 255, 0.18);
  border-radius: 50%;
}

.aside-foot {
  margin: auto 0 0;
  padding-top: 36px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 11.5px;
  letter-spacing: 0.08em;
}

.auth-main {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 44px;
}

.title {
  margin: 0 0 6px;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 24px;
  letter-spacing: -0.02em;
}

.desc {
  margin: 0 0 26px;
  color: var(--ink-400);
  font-size: 13.5px;
}

.captcha-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.captcha-image {
  display: block;
  width: 120px;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
}

.captcha-refresh {
  height: 36px;
  padding: 0 12px;
  color: var(--ink-500);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
}

.tips {
  margin: 2px 0 18px;
  color: var(--ink-400);
  font-size: 13px;
  text-align: right;
}

.submit-btn {
  width: 100%;
  height: 44px;
  border-radius: 12px;
  font-weight: 600;
}

@media (max-width: 860px) {
  .auth-page {
    min-height: auto;
  }

  .auth-card {
    grid-template-columns: 1fr;
  }

  .auth-aside {
    display: none;
  }

  .auth-main {
    padding: 34px 24px;
  }
}
</style>
