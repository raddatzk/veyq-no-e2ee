import { reactive } from 'vue'

export const serverState = reactive({
  serverId: '',
  setupCompleted: false,
  mobileApps: {
    iosAppStoreId: undefined as string | undefined
  }
})
