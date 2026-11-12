import { describe, expect, it } from 'vitest'
import {
  checkQuotaForUpload,
  describePictureQuota,
  describeStorageQuota,
  summarizeQuota,
} from '../quota'

describe('summarizeQuota', () => {
  it('把字符串数值统一成数字并计算剩余量与百分比', () => {
    const summary = summarizeQuota({
      storageUsed: '1073741824',
      storageQuota: '2147483648',
      pictureUsed: 120,
      pictureQuota: 1000,
      exempt: false,
    })

    expect(summary.usedBytes).toBe(1073741824)
    expect(summary.quotaBytes).toBe(2147483648)
    expect(summary.remainingBytes).toBe(1073741824)
    expect(summary.percent).toBe(50)
    expect(summary.exceeded).toBe(false)
    expect(summary.exempt).toBe(false)
  })

  it('缺少配额数据时按 0 处理', () => {
    const summary = summarizeQuota(null)

    expect(summary.usedBytes).toBe(0)
    expect(summary.quotaBytes).toBe(0)
    expect(summary.remainingBytes).toBe(0)
    expect(summary.percent).toBe(0)
    expect(summary.exceeded).toBe(false)
  })

  it('存储或数量任一到达上限即视为超限，管理员豁免除外', () => {
    expect(
      summarizeQuota({ storageUsed: '100', storageQuota: '100', pictureUsed: 1, pictureQuota: 10 })
        .exceeded,
    ).toBe(true)
    expect(
      summarizeQuota({ storageUsed: '1', storageQuota: '100', pictureUsed: 10, pictureQuota: 10 })
        .exceeded,
    ).toBe(true)
    expect(
      summarizeQuota({
        storageUsed: '100',
        storageQuota: '100',
        pictureUsed: 10,
        pictureQuota: 10,
        exempt: true,
      }).exceeded,
    ).toBe(false)
  })
})

describe('describeQuota', () => {
  const summary = summarizeQuota({
    storageUsed: String(1.2 * 1024 * 1024 * 1024),
    storageQuota: String(1 * 1024 * 1024 * 1024),
    pictureUsed: 12,
    pictureQuota: 100,
  })

  it('存储文案包含已用与上限', () => {
    expect(describeStorageQuota(summary)).toBe('1.2GB / 1GB')
  })

  it('数量文案包含已用与上限', () => {
    expect(describePictureQuota(summary)).toBe('12 / 100 张')
  })
})

describe('checkQuotaForUpload', () => {
  it('未登录/未取到配额时不拦截，交给后端校验', () => {
    expect(checkQuotaForUpload(null, 1024)).toBeNull()
  })

  it('管理员豁免时不拦截', () => {
    const summary = summarizeQuota({ storageUsed: '100', storageQuota: '100', exempt: true })
    expect(checkQuotaForUpload(summary, 1024)).toBeNull()
  })

  it('图片数量到达上限时提示数量文案', () => {
    const summary = summarizeQuota({
      storageUsed: '0',
      storageQuota: '1024',
      pictureUsed: 5,
      pictureQuota: 5,
    })

    expect(checkQuotaForUpload(summary, 1)).toContain('图片数量已达上限')
  })

  it('文件超过剩余空间时提示已用/上限', () => {
    const summary = summarizeQuota({
      storageUsed: String(900 * 1024 * 1024),
      storageQuota: String(1000 * 1024 * 1024),
      pictureUsed: 0,
      pictureQuota: 10,
    })

    const error = checkQuotaForUpload(summary, 200 * 1024 * 1024)
    expect(error).toContain('存储空间不足')
    expect(error).toContain('900MB / 1000MB')
  })

  it('空间足够时放行', () => {
    const summary = summarizeQuota({
      storageUsed: '0',
      storageQuota: String(1024 * 1024 * 1024),
      pictureUsed: 0,
      pictureQuota: 10,
    })

    expect(checkQuotaForUpload(summary, 2 * 1024 * 1024)).toBeNull()
  })
})
