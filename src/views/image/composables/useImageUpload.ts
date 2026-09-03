import { ref } from 'vue'

export function useImageUpload() {
  const isDragging = ref(false)
  const uploadError = ref('')
  const fileInput = ref<HTMLInputElement | null>(null)

  return {
    isDragging,
    uploadError,
    fileInput,
  }
}
