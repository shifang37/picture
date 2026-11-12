import { describe, expect, it } from 'vitest'
import { checkPasswordStrength } from '../password'

describe('checkPasswordStrength', () => {
  it('接受同时包含字母和数字的 8-64 位密码', () => {
    expect(checkPasswordStrength('Abcdef12')).toBeNull()
    expect(checkPasswordStrength('a1b2c3d4')).toBeNull()
  })

  it('拒绝过短或过长的密码', () => {
    expect(checkPasswordStrength('Abc123')).toBe('密码长度不能少于 8 位')
    expect(checkPasswordStrength('a1'.repeat(33))).toBe('密码长度不能超过 64 位')
  })

  it('拒绝只有字母或只有数字的密码', () => {
    expect(checkPasswordStrength('abcdefgh')).toBe('密码必须同时包含字母和数字')
    expect(checkPasswordStrength('12345678')).toBe('密码必须同时包含字母和数字')
  })

  it('拒绝空白字符与常见弱口令', () => {
    expect(checkPasswordStrength('Abcdef 12')).toBe('密码不能包含空白字符')
    expect(checkPasswordStrength('password1')).toBe('密码过于简单，请更换更复杂的密码')
  })

  it('按 UTF-8 字节数限制 BCrypt 输入', () => {
    expect(checkPasswordStrength('中'.repeat(30) + '12')).toBe('密码不能超过 72 字节')
  })
})
