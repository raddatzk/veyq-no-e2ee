import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'

const NoSpaceAvailableErrorMock = vi.hoisted(
  () =>
    class NoSpaceAvailableError extends Error {
      constructor() {
        super('no space available')
      }
    }
)

const mocks = vi.hoisted(() => ({
  ensureActiveSpaceForRoute: vi.fn(),
  loadLevel: vi.fn(),
  requireSession: vi.fn()
}))

vi.mock('../app/spaces', () => ({
  NoSpaceAvailableError: NoSpaceAvailableErrorMock,
  ensureActiveSpaceForRoute: mocks.ensureActiveSpaceForRoute
}))

vi.mock('../features/home/home', () => ({
  loadLevel: mocks.loadLevel
}))

vi.mock('../app/session', () => ({
  requireSession: mocks.requireSession,
  session: { apiBase: '', activeSpace: null }
}))

describe('HomeView', () => {
  beforeEach(() => {
    mocks.ensureActiveSpaceForRoute.mockReset()
    mocks.loadLevel.mockReset()
    mocks.requireSession.mockReset()
  })

  it('shows the no-tenant state instead of the personal memories view when no space is enterable', async () => {
    mocks.ensureActiveSpaceForRoute.mockRejectedValue(new NoSpaceAvailableErrorMock())

    const wrapper = mount(HomeView, {
      global: { stubs: { Button: true, Message: true, ProgressSpinner: true, NodeCover: true } }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Noch kein Tenant')
    expect(wrapper.text()).not.toContain('Meine Memories')
    expect(mocks.loadLevel).not.toHaveBeenCalled()
  })
})
