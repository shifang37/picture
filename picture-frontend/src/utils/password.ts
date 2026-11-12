// 密码强度规则：与后端 PasswordPolicy 保持一致，用于表单即时校验。
// 后端仍会再校验一次，前端提示只负责减少无效请求。

export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 64
export const PASSWORD_BCRYPT_MAX_BYTES = 72
export const PASSWORD_HINT = `${PASSWORD_MIN_LENGTH}-${PASSWORD_MAX_LENGTH} 位，需同时包含字母和数字`

// 与后端 PasswordPolicy.COMMON_PASSWORDS 保持一致
const COMMON_PASSWORDS = new Set([
  '12345678',
  '123456789',
  'password',
  'password1',
  'password123',
  'qwerty123',
  'abc12345',
  'admin123',
  '11111111',
  'a1234567',
  'iloveyou',
  '88888888',
  '66666666',
  'aa123456',
  'abc123456',
])

/**
 * 校验密码强度
 * @returns 不合规时返回提示信息，合规返回 null
 */
export function checkPasswordStrength(password: string): string | null {
  if (!password) {
    return '密码不能为空'
  }
  // 与后端 codePointCount 一致，按字符而不是 UTF-16 码元计数
  const length = Array.from(password).length
  if (length < PASSWORD_MIN_LENGTH) {
    return `密码长度不能少于 ${PASSWORD_MIN_LENGTH} 位`
  }
  if (length > PASSWORD_MAX_LENGTH) {
    return `密码长度不能超过 ${PASSWORD_MAX_LENGTH} 位`
  }
  // 后端用 BCrypt 存储，上限是 72 字节而不是字符
  if (new TextEncoder().encode(password).length > PASSWORD_BCRYPT_MAX_BYTES) {
    return `密码不能超过 ${PASSWORD_BCRYPT_MAX_BYTES} 字节`
  }
  if (/\s/.test(password)) {
    return '密码不能包含空白字符'
  }
  if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) {
    return '密码必须同时包含字母和数字'
  }
  if (COMMON_PASSWORDS.has(password.toLowerCase())) {
    return '密码过于简单，请更换更复杂的密码'
  }
  return null
}

/**
 * Ant Design Vue 表单校验规则：空值交给 required 规则处理
 */
export const passwordStrengthRule = {
  validator: (_rule: unknown, value: string) => {
    if (!value) {
      return Promise.resolve()
    }
    const error = checkPasswordStrength(value)
    return error ? Promise.reject(error) : Promise.resolve()
  },
}
