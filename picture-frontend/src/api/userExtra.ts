// 手动补充的用户接口封装。
// 生成式客户端（userController.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/**
 * 获取注册用图形验证码（一次性，5 分钟内有效）
 */
export async function getRegisterCaptcha(options?: { [key: string]: any }) {
  return request<API.BaseResponseCaptchaVO>('/user/captcha', {
    method: 'GET',
    ...(options || {}),
  })
}

export interface UserUpdateProfileParams {
  userName?: string
  userAvatar?: string
  userProfile?: string
}

/**
 * 修改当前登录用户的个人资料（普通用户可用）
 */
export async function updateProfile(
  body: UserUpdateProfileParams,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean>('/user/update/profile', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

export interface UpdatePasswordParams {
  oldPassword: string
  newPassword: string
}

/**
 * 修改当前登录用户的密码
 */
export async function updatePassword(body: UpdatePasswordParams, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/user/update/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

export interface UserResetPasswordParams {
  id: string
  newPassword: string
}

/**
 * 管理员重置指定用户的密码
 */
export async function resetUserPassword(
  body: UserResetPasswordParams,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseBoolean>('/user/reset/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 公开作者主页信息：只包含公开资料与已过审作品的聚合统计。
 * 后端将 Long 序列化为字符串，统计值统一用 Number() 转换。
 */
export interface PublicUserProfile {
  user?: API.PublicUserVO
  pictureCount?: string | number
  downloadCount?: string | number
  favoriteCount?: string | number
  likeCount?: string | number
  joinedAt?: string
}

interface BaseResponsePublicUserProfile {
  code?: number
  data?: PublicUserProfile
  message?: string
}

/**
 * 获取公开作者主页信息（匿名可访问）
 */
export async function getPublicUserProfile(id: string, options?: { [key: string]: any }) {
  return request<BaseResponsePublicUserProfile>(`/user/public/${encodeURIComponent(id)}`, {
    method: 'GET',
    ...(options || {}),
  })
}
