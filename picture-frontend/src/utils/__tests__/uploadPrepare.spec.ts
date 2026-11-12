import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/utils/heic', () => ({
  isHeicFile: vi.fn(() => false),
  convertHeicToJpeg: vi.fn(async () => new File(['converted'], 'photo.jpg', { type: 'image/jpeg' })),
}))

import { convertHeicToJpeg, isHeicFile } from '@/utils/heic'
import { MAX_UPLOAD_BYTES, prepareImageForUpload, validateImageFile } from '../uploadPrepare'

const createFile = (name = 'photo.jpg', type = 'image/jpeg', size = 1024) => {
  const file = new File([new Uint8Array(Math.min(size, 1024))], name, { type })
  if (size > 1024) {
    Object.defineProperty(file, 'size', { value: size })
  }
  return file
}

describe('validateImageFile', () => {
  it('允许 jpg / png / webp，后缀大小写不敏感', () => {
    expect(validateImageFile(createFile('a.jpg'))).toBeNull()
    expect(validateImageFile(createFile('a.JPG', 'image/jpeg'))).toBeNull()
    expect(validateImageFile(createFile('a.png', 'image/png'))).toBeNull()
    expect(validateImageFile(createFile('a.webp', 'image/webp'))).toBeNull()
  })

  it('允许 HEIC / HEIF，交给转换流程处理', () => {
    expect(validateImageFile(createFile('IMG_0001.HEIC', 'image/heic'))).toBeNull()
    expect(validateImageFile(createFile('IMG_0001.heif', ''))).toBeNull()
  })

  it('拒绝不支持的格式与错误的 MIME', () => {
    expect(validateImageFile(createFile('a.gif', 'image/gif'))).toBe(
      '仅支持 jpg、jpeg、png、webp、heic 格式的图片',
    )
    expect(validateImageFile(createFile('a.jpg', 'text/plain'))).toBe(
      '仅支持 jpg、jpeg、png、webp、heic 格式的图片',
    )
  })

  it('拒绝空文件与超过 10MB 的文件', () => {
    expect(validateImageFile(createFile('a.jpg', 'image/jpeg', 0))).toBe('不能上传空文件')
    expect(validateImageFile(createFile('a.jpg', 'image/jpeg', MAX_UPLOAD_BYTES + 1))).toBe(
      '不能上传超过 10MB 的图片',
    )
    expect(validateImageFile(createFile('a.jpg', 'image/jpeg', MAX_UPLOAD_BYTES))).toBeNull()
  })
})

describe('prepareImageForUpload', () => {
  beforeEach(() => {
    vi.mocked(isHeicFile).mockReturnValue(false)
    vi.mocked(convertHeicToJpeg).mockClear()
    // jsdom 没有真实的图片解码能力：让压缩流程快速回退原文件
    vi.stubGlobal(
      'createImageBitmap',
      vi.fn(async () => {
        throw new Error('decode failed')
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('普通小图不转换不压缩，按压缩阶段回调', async () => {
    const stages: string[] = []
    const file = createFile('small.jpg')

    const prepared = await prepareImageForUpload(file, {
      onStage: (stage) => stages.push(stage),
    })

    expect(prepared.file).toBe(file)
    expect(prepared.converted).toBe(false)
    expect(prepared.compression).toBeNull()
    expect(stages).toEqual(['compressing'])
  })

  it('HEIC 先转 JPEG 再进入压缩流程', async () => {
    vi.mocked(isHeicFile).mockReturnValue(true)
    const stages: string[] = []
    const file = createFile('IMG_0001.heic', 'image/heic')

    const prepared = await prepareImageForUpload(file, {
      onStage: (stage) => stages.push(stage),
    })

    expect(convertHeicToJpeg).toHaveBeenCalledWith(file)
    expect(prepared.converted).toBe(true)
    expect(prepared.file.name).toBe('photo.jpg')
    expect(prepared.originalSize).toBe(file.size)
    expect(stages).toEqual(['converting', 'compressing'])
  })

  it('HEIC 转换失败时抛出带引导信息的错误', async () => {
    vi.mocked(isHeicFile).mockReturnValue(true)
    vi.mocked(convertHeicToJpeg).mockRejectedValueOnce(
      new Error('HEIC 转换失败，请确认照片未损坏，或先转换为 JPG 再上传'),
    )

    await expect(
      prepareImageForUpload(createFile('broken.heic', 'image/heic')),
    ).rejects.toThrow('HEIC 转换失败')
  })
})
