import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  compressImage,
  computeTargetSize,
  DEFAULT_MAX_DIMENSION,
  describeCompression,
  shouldCompress,
} from '../image'

const createImageFile = (name = 'photo.jpg', size = 3 * 1024 * 1024) =>
  new File([new Uint8Array(size)], name, { type: 'image/jpeg' })

describe('computeTargetSize', () => {
  it('长边超过 1920 时等比缩小', () => {
    expect(computeTargetSize(3000, 2000)).toEqual({ width: 1920, height: 1280 })
    expect(computeTargetSize(2000, 3000)).toEqual({ width: 1280, height: 1920 })
  })

  it('未超过时保持原尺寸，不放大小图', () => {
    expect(computeTargetSize(800, 600)).toEqual({ width: 800, height: 600 })
    expect(computeTargetSize(1920, 1080)).toEqual({ width: 1920, height: 1080 })
  })

  it('非法尺寸至少保留 1 像素', () => {
    expect(computeTargetSize(0, 0)).toEqual({ width: 1, height: 1 })
  })

  it('自定义长边上限', () => {
    expect(computeTargetSize(4000, 2000, 1000)).toEqual({ width: 1000, height: 500 })
  })
})

describe('shouldCompress', () => {
  it('体积超过 2MB 时需要压缩', () => {
    expect(shouldCompress(3 * 1024 * 1024, null)).toBe(true)
    expect(shouldCompress(1 * 1024 * 1024, null)).toBe(false)
  })

  it('长边超过 1920 时需要压缩，即使体积不大', () => {
    expect(shouldCompress(500 * 1024, { width: 3000, height: 1000 })).toBe(true)
    expect(shouldCompress(500 * 1024, { width: DEFAULT_MAX_DIMENSION, height: 500 })).toBe(false)
  })

  it('未知尺寸时只看体积', () => {
    expect(shouldCompress(500 * 1024, undefined)).toBe(false)
    expect(shouldCompress(5 * 1024 * 1024, undefined)).toBe(true)
  })

  it('自定义阈值生效', () => {
    expect(shouldCompress(1.5 * 1024 * 1024, null, { maxSizeMB: 1 })).toBe(true)
    expect(shouldCompress(500 * 1024, { width: 1200, height: 800 }, { maxWidthOrHeight: 1000 })).toBe(
      true,
    )
  })
})

describe('describeCompression', () => {
  it('输出“原体积 → 压缩后体积”', () => {
    expect(
      describeCompression({
        file: createImageFile(),
        compressed: true,
        originalSize: 3.2 * 1024 * 1024,
        size: 1.8 * 1024 * 1024,
      }),
    ).toBe('3.2MB → 1.8MB')
  })
})

describe('compressImage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('非图片文件原样返回', async () => {
    const file = new File(['hello'], 'a.txt', { type: 'text/plain' })

    const result = await compressImage(file)

    expect(result.compressed).toBe(false)
    expect(result.file).toBe(file)
  })

  it('无法读取尺寸时回退原文件，不抛异常', async () => {
    // 模拟浏览器解码失败（jsdom 本身没有真实的图片解码能力）
    vi.stubGlobal(
      'createImageBitmap',
      vi.fn(async () => {
        throw new Error('decode failed')
      }),
    )
    const file = createImageFile('broken.jpg', 4 * 1024 * 1024)

    const result = await compressImage(file)

    expect(result.compressed).toBe(false)
    expect(result.file).toBe(file)
    expect(result.originalSize).toBe(file.size)
  })

  it('大图经 Canvas 重新编码后体积下降，PNG 保留原格式', async () => {
    vi.stubGlobal(
      'createImageBitmap',
      vi.fn(async () => ({ width: 3000, height: 2000, close: vi.fn() })),
    )
    const encodedBytes = new Uint8Array(1024 * 1024)
    const drawImage = vi.fn()
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      if (tagName !== 'canvas') {
        return originalCreateElement(tagName)
      }
      return {
        width: 0,
        height: 0,
        getContext: () => ({
          drawImage,
          imageSmoothingEnabled: false,
          imageSmoothingQuality: 'low',
        }),
        toBlob: (callback: (blob: Blob | null) => void, type?: string) =>
          callback(new Blob([encodedBytes], { type: type ?? 'image/jpeg' })),
      } as unknown as HTMLCanvasElement
    })

    const file = new File([new Uint8Array(3 * 1024 * 1024)], 'big.png', { type: 'image/png' })
    const result = await compressImage(file, { maxSizeMB: 2, maxWidthOrHeight: 1920 })

    expect(result.compressed).toBe(true)
    expect(result.size).toBe(encodedBytes.length)
    // PNG 保留透明通道，输出仍是 PNG
    expect(result.file.type).toBe('image/png')
    expect(result.file.name).toBe('big.png')
    expect(drawImage).toHaveBeenCalledTimes(1)
  })

  it('压缩结果反而更大时回退原文件', async () => {
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 3000, height: 2000, close: vi.fn() })))
    const huge = new Blob([new Uint8Array(5 * 1024 * 1024)], { type: 'image/jpeg' })
    const originalCreateElement = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      if (tagName !== 'canvas') {
        return originalCreateElement(tagName)
      }
      return {
        width: 0,
        height: 0,
        getContext: () => ({ drawImage: vi.fn(), imageSmoothingEnabled: false }),
        toBlob: (callback: (blob: Blob | null) => void) => callback(huge),
      } as unknown as HTMLCanvasElement
    })
    const file = createImageFile('big.jpg', 3 * 1024 * 1024)

    const result = await compressImage(file)

    expect(result.compressed).toBe(false)
    expect(result.file).toBe(file)
  })
})
