import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { setUnlocked } from '../session'
import SupportNotice from './SupportNotice.vue'

beforeEach(() => {
  localStorage.clear()
  setUnlocked(null)
})

describe('SupportNotice', () => {
  it('is visible only for server owners and can be dismissed locally', async () => {
    setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerMember' } } as never)
    expect(mount(SupportNotice).text()).toBe('')

    setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@example.com', displayName: 'Kevin', serverRole: 'ServerOwner' } } as never)
    const wrapper = mount(SupportNotice)
    expect(wrapper.text()).toContain('veyq ist freie Software')
    expect(wrapper.find('a[href="https://github.com/sponsors/raddatzk"]').exists()).toBe(true)

    await wrapper.find('button').trigger('click')
    expect(wrapper.text()).toBe('')
    expect(localStorage.getItem('veyq.supportNotice.dismissed.v1')).toBe('true')
  })
})
