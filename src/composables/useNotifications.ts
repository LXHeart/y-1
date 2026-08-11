import { storeToRefs } from 'pinia'
import { useNotificationStore, resolveLinkTarget } from '../stores/notifications'

export { resolveLinkTarget }

export function useNotifications() {
  const store = useNotificationStore()
  return { ...store, ...storeToRefs(store) }
}
