import { storeToRefs } from 'pinia'
import { useAuthStore } from '../stores/auth'

export function useAuth() {
  const store = useAuthStore()
  return { ...store, ...storeToRefs(store) }
}
