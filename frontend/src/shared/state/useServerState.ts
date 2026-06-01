import { serverState } from '../../serverState'

export function useServerState() {
  function setSetupCompleted(value: boolean) {
    serverState.setupCompleted = value
  }

  function setServerId(id: string) {
    serverState.serverId = id
  }

  return {
    serverState,
    setSetupCompleted,
    setServerId,
  }
}
