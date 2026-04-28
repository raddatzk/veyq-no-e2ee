import { serverState } from '../../serverState'

export function useServerState() {
  function setSetupCompleted(value: boolean) {
    serverState.setupCompleted = value
  }

  return {
    serverState,
    setSetupCompleted
  }
}
