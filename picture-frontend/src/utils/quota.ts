/**
 * 配额展示与判断：个人中心进度条、上传前预检查共用。
 */
import { formatBytes, percentOf } from '@/utils/size'
import type { UserQuota } from '@/api/quotaExtra'

export interface QuotaSummary {
  /** 已用存储（字节） */
  usedBytes: number
  /** 存储配额（字节） */
  quotaBytes: number
  /** 剩余存储（字节），不会为负 */
  remainingBytes: number
  /** 已用百分比 0–100 */
  percent: number
  /** 已用数量 */
  usedPictures: number
  /** 数量上限 */
  quotaPictures: number
  /** 是否已超限（存储或数量） */
  exceeded: boolean
  /** 是否不受配额限制 */
  exempt: boolean
}

/**
 * 把接口返回的配额字符串/数字统一成可计算的结构
 */
export function summarizeQuota(quota?: UserQuota | null): QuotaSummary {
  const usedBytes = toNumber(quota?.storageUsed)
  const quotaBytes = toNumber(quota?.storageQuota)
  const usedPictures = toNumber(quota?.pictureUsed)
  const quotaPictures = toNumber(quota?.pictureQuota)
  const exempt = Boolean(quota?.exempt)
  return {
    usedBytes,
    quotaBytes,
    remainingBytes: Math.max(0, quotaBytes - usedBytes),
    percent: percentOf(usedBytes, quotaBytes),
    usedPictures,
    quotaPictures,
    exceeded:
      !exempt &&
      ((quotaBytes > 0 && usedBytes >= quotaBytes) ||
        (quotaPictures > 0 && usedPictures >= quotaPictures)),
    exempt,
  }
}

/**
 * 存储用量文案：1.2GB / 1GB
 */
export function describeStorageQuota(summary: QuotaSummary): string {
  return `${formatBytes(summary.usedBytes)} / ${formatBytes(summary.quotaBytes)}`
}

/**
 * 数量用量文案：120 / 1000 张
 */
export function describePictureQuota(summary: QuotaSummary): string {
  return `${summary.usedPictures} / ${summary.quotaPictures} 张`
}

/**
 * 上传前判断：返回错误文案；可以上传时返回 null
 *
 * @param summary    配额摘要
 * @param fileBytes  即将上传的文件体积
 */
export function checkQuotaForUpload(summary: QuotaSummary | null, fileBytes: number): string | null {
  if (!summary || summary.exempt) {
    return null
  }
  if (summary.quotaPictures > 0 && summary.usedPictures >= summary.quotaPictures) {
    return `图片数量已达上限（${describePictureQuota(summary)}），请联系管理员调整配额`
  }
  if (fileBytes > summary.remainingBytes) {
    return `存储空间不足（已用 ${describeStorageQuota(summary)}），请联系管理员调整配额`
  }
  return null
}

function toNumber(value: string | number | null | undefined): number {
  if (value == null) {
    return 0
  }
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}
