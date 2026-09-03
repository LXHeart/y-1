import { ref } from 'vue'

export function useImagePreview() {
  const previewIndex = ref<number | null>(null)

  function previewImage(index: number): void {
    previewIndex.value = index
  }

  function closePreview(): void {
    previewIndex.value = null
  }

  return {
    previewIndex,
    previewImage,
    closePreview,
  }
}
