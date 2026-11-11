import axios from 'axios'
import { message } from 'ant-design-vue'

// 创建 Axios 实例
// 说明：后端 JsonConfig 已统一把 Long 序列化为字符串，雪花 ID 不存在精度丢失问题，
// 因此这里不再对响应体做正则改写，直接使用 axios 默认的 JSON 解析。
const myAxios = axios.create({
  baseURL: '/api',
  timeout: 60000,
  withCredentials: true,
  // 与后端 CsrfFilter 的 double-submit cookie 约定保持一致
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        // 使用站内相对路径并编码，登录页只接受以 / 开头的 redirect
        const current = window.location.pathname + window.location.search
        window.location.href = `/user/login?redirect=${encodeURIComponent(current)}`
      }
    }
    // CSRF 校验失败：通常是页面停留过久 cookie 丢失，刷新后重新获取 token
    if (data.code === 40300) {
      message.error(data.message || '请求校验失败，请刷新页面后重试')
    }
    // 登录 / 上传等接口触发限流
    if (data.code === 42900) {
      message.warning(data.message || '请求过于频繁，请稍后再试')
    }
    return response
  },
  function (error) {
    // Any status code that falls outside the range of 2xx cause this function to trigger
    // Do something with response error
    return Promise.reject(error)
  },
)

export default myAxios
