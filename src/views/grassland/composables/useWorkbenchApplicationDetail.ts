import { computed, ref, watch } from 'vue'
import type { useGrassland } from '../../../composables/useGrassland'
import { useAccountSessionStore } from '../../../stores/account-session'
import type { ApplicationSettlement, MyApplication, Task } from '../../../types/grassland'

export function useWorkbenchApplicationDetail(
  grassland: ReturnType<typeof useGrassland>,
  props: { taskId: string; task: Task | null; myApplication: MyApplication | null },
) {
  const session = useAccountSessionStore()
  const task = ref<Task | null>(null)
  const application = ref<MyApplication | null>(null)
  const settlement = ref<ApplicationSettlement | null>(null)
  const loading = ref(false)
  const termsAccepted = ref(false)
  let sequence = 0
  const canReconsent = computed(() => termsAccepted.value && settlement.value?.allowedActions.includes('reconsent'))

  async function load(): Promise<void> {
    const ticket = session.capture()
    const request = ++sequence
    const taskId = props.taskId
    const current = () => session.isCurrent(ticket) && request === sequence && props.taskId === taskId
    loading.value = true
    task.value = props.task?.id === taskId ? props.task : null
    application.value = null
    settlement.value = null
    termsAccepted.value = false
    try {
      if (!task.value || props.myApplication?.applicationStatus === 'reconsent') {
        const detail = await grassland.getTask(taskId)
        if (!current()) return
        if (detail) task.value = detail
      }
      if (!task.value) return
      const known = props.myApplication?.taskId === taskId ? props.myApplication : null
      const app = known
        ? await grassland.getApplication(taskId, known.applicationId)
        : (await grassland.listApplicationsPage(taskId, undefined, 1))?.items[0]
      if (!current() || !app) return
      application.value = {
        applicationId: app.id, taskId, taskTitle: task.value.title, taskStatus: task.value.status,
        applicationStatus: app.status, bountyCents: task.value.bountyCents ?? 0,
        appliedAt: app.createdAt, settledAt: known?.settledAt ?? null,
      }
      const state = await grassland.getApplicationSettlement(app.id)
      if (!current()) return
      settlement.value = state
    } finally {
      if (current()) loading.value = false
    }
  }

  async function reconsent(): Promise<void> {
    if (!canReconsent.value || loading.value || !application.value) return
    const ticket = session.capture()
    const request = sequence
    const app = application.value
    loading.value = true
    try {
      const updated = await grassland.reconsentApplication(app.taskId, app.applicationId)
      if (!session.isCurrent(ticket) || request !== sequence || !updated) return
      await load()
    } finally {
      if (session.isCurrent(ticket) && request === sequence) loading.value = false
    }
  }

  watch(() => [props.taskId, props.myApplication, session.epoch], () => { void load() }, { immediate: true })
  return { task, application, settlement, loading, termsAccepted, canReconsent, load, reconsent }
}
