import { serverState } from '../../serverState'

export function useServerState() {
  function setSetupCompleted(value: boolean) {
    serverState.setupCompleted = value
  }

  function setServerId(id: string) {
    serverState.serverId = id
  }

  function setMobileApps(value: { iosAppStoreId?: string }) {
    serverState.mobileApps.iosAppStoreId = value.iosAppStoreId
  }

  return {
    serverState,
    setSetupCompleted,
    setServerId,
    setMobileApps,
  }
}
