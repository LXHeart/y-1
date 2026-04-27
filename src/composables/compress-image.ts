const MAX_COMPRESS_ITERATIONS = 10
const INITIAL_QUALITY = 0.8
const SCALE_FACTOR = 0.8
const TARGET_MIME = 'image/jpeg'

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片加载失败'))
    img.src = src
  })
}

function drawToCanvas(img: HTMLImageElement, scale: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(img.naturalWidth * scale)
  canvas.height = Math.round(img.naturalHeight * scale)
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas 上下文创建失败')
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
  return canvas
}

function canvasToBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) resolve(blob)
        else reject(new Error('Canvas 导出失败'))
      },
      TARGET_MIME,
      quality,
    )
  })
}

export async function compressImageToFile(
  file: File,
  maxSizeBytes: number,
): Promise<File> {
  const dataUrl = URL.createObjectURL(file)
  try {
    const img = await loadImage(dataUrl)

    let scale = 1
    let quality = INITIAL_QUALITY
    let result: Blob | null = null

    for (let i = 0; i < MAX_COMPRESS_ITERATIONS; i += 1) {
      const canvas = drawToCanvas(img, scale)
      const blob = await canvasToBlob(canvas, quality)

      if (blob.size <= maxSizeBytes) {
        result = blob
        break
      }

      scale *= SCALE_FACTOR
      if (scale < 0.1) {
        scale = 0.1
        quality = Math.max(0.3, quality - 0.1)
      }
    }

    if (!result) {
      const canvas = drawToCanvas(img, 0.1)
      result = await canvasToBlob(canvas, 0.3)
    }

    const baseName = file.name.replace(/\.[^.]+$/u, '')
    return new File([result], `${baseName}.jpg`, { type: TARGET_MIME })
  } finally {
    URL.revokeObjectURL(dataUrl)
  }
}
