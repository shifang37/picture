/**
 * 首页瀑布流布局的纯计算工具。
 *
 * 卡片高度在图片加载前就可以确定：后端保存了 picWidth / picHeight / picScale，
 * 这里用「高 / 宽」的比例做列分配，不测量 DOM，因此不会在图片加载完成时产生布局跳动。
 */

/** 列间距（px），需与 HomePage 的 CSS 保持一致 */
export const WATERFALL_GAP = 16

/** 单列最小宽度（px） */
export const WATERFALL_MIN_COLUMN_WIDTH = 240

/** 最多列数，与改动前的栅格上限一致 */
export const WATERFALL_MAX_COLUMNS = 6

/** 卡片下方文字区的高度，按列宽的倍数估算，用于列分配 */
const CARD_INFO_RATIO = 0.28

/** 宽高信息不完整的图片按正方形处理 */
const FALLBACK_ASPECT_RATIO = 1

export interface PictureDimensions {
  picWidth?: number
  picHeight?: number
}

/**
 * 根据容器宽度计算列数。
 * 容器宽度未知（首帧、隐藏状态）时按 1200px 估算，避免退化成单列。
 */
export function getColumnCount(containerWidth: number): number {
  const width = Number.isFinite(containerWidth) && containerWidth > 0 ? containerWidth : 1200
  const count = Math.floor((width + WATERFALL_GAP) / (WATERFALL_MIN_COLUMN_WIDTH + WATERFALL_GAP))
  return Math.min(WATERFALL_MAX_COLUMNS, Math.max(1, count))
}

/**
 * 单列宽度，供缩略图按列宽选择尺寸。
 */
export function getColumnWidth(containerWidth: number, columnCount: number): number {
  const count = Math.max(1, Math.floor(columnCount))
  const width = Number.isFinite(containerWidth) && containerWidth > 0 ? containerWidth : 0
  return Math.max(0, (width - WATERFALL_GAP * (count - 1)) / count)
}

/**
 * 图片的「高 / 宽」比例。宽或高缺失、非法时回退为 1。
 */
export function getPictureAspectRatio(picture: PictureDimensions): number {
  const width = Number(picture.picWidth)
  const height = Number(picture.picHeight)
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return FALLBACK_ASPECT_RATIO
  }
  return height / width
}

/**
 * 按「放入当前最矮的一列」分配图片，保持每列内部的原始顺序。
 *
 * @param items 图片列表（按接口返回顺序）
 * @param columnCount 列数
 * @param getRatio 取图片高宽比
 */
export function distributeToColumns<T>(
  items: readonly T[],
  columnCount: number,
  getRatio: (item: T) => number,
): T[][] {
  const count = Math.max(1, Math.floor(columnCount))
  const columns: T[][] = Array.from({ length: count }, () => [])
  const heights = new Array<number>(count).fill(0)

  for (const item of items) {
    let target = 0
    let targetHeight = heights[0] ?? 0
    for (let index = 1; index < count; index += 1) {
      const height = heights[index] ?? 0
      if (height < targetHeight) {
        target = index
        targetHeight = height
      }
    }
    const column = columns[target]
    if (!column) {
      continue
    }
    column.push(item)
    const ratio = getRatio(item)
    const safeRatio = Number.isFinite(ratio) && ratio > 0 ? ratio : FALLBACK_ASPECT_RATIO
    heights[target] = targetHeight + safeRatio + CARD_INFO_RATIO
  }

  return columns
}

/**
 * 按列宽和设备像素比选择缩略图最长边，限制在 300–600，避免生成过多尺寸。
 */
export function getThumbnailSize(columnWidth: number, devicePixelRatio = 1): number {
  const width =
    Number.isFinite(columnWidth) && columnWidth > 0 ? columnWidth : WATERFALL_MIN_COLUMN_WIDTH
  const dpr = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1
  const rounded = Math.round((width * dpr) / 100) * 100
  return Math.min(600, Math.max(300, rounded))
}
