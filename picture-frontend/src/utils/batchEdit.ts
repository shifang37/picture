/**
 * 批量编辑弹窗的表单 → 请求参数转换。
 * <p>
 * 关键语义：没动过的字段不提交（避免误覆盖），标签「动过但为空」提交空数组（表示清空）。
 * 抽成纯函数，便于 Vitest 覆盖这些容易写错的边界。
 */

export interface BatchEditFormState {
  /** 选中的分类；undefined 表示不修改 */
  category?: string
  /** 标签；是否提交由 tagsTouched 决定 */
  tags?: string[]
  /** 用户是否动过标签选择框 */
  tagsTouched: boolean
  /** 简介输入框内容；空串表示不修改 */
  introduction: string
}

export interface BatchEditPayload {
  category?: string
  tags?: string[]
  introduction?: string
  /** 是否至少有一个字段需要提交 */
  hasChanges: boolean
}

/**
 * 表单值转批量编辑请求：
 * - 分类、简介会去掉首尾空白，空串不提交；
 * - 标签只有在用户动过时才提交，空数组表示清空；
 * - 所有字段都没填时 hasChanges = false，调用方据此提示用户。
 */
export function buildBatchEditPayload(form: BatchEditFormState): BatchEditPayload {
  const category = form.category?.trim() ? form.category.trim() : undefined
  const introduction = form.introduction.trim() ? form.introduction.trim() : undefined
  const tags = form.tagsTouched ? [...(form.tags ?? [])] : undefined
  return {
    category,
    tags,
    introduction,
    hasChanges: category !== undefined || tags !== undefined || introduction !== undefined,
  }
}
