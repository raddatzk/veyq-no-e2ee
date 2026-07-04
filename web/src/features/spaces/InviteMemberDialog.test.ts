// @vitest-environment happy-dom
// uc.spaces.add-member (web, cr-0077/cr-0080) — the invite-member dialog flow: it shapes the inviteMember call
// (email trimmed + lowercased), on success emits `invited` and switches to the copy-link view (cr-0080 — the
// owner hands the link off, no mailer), and surfaces already_member / not-owner distinctly.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('./members', () => ({
  inviteMember: vi.fn().mockResolvedValue({ email: 'x@y.z', inviteStatus: 'ACTIVE', inviteExpiresAt: new Date(), bootstrapSecret: 'tok123' }),
  memberError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))

import InviteMemberDialog from './InviteMemberDialog.vue'
import { inviteMember, memberError } from './members'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { cancel: 'Cancel', done: 'Done' },
      members: {
        invite: 'Invite member', inviteIntro: 'intro', email: 'Email', inviteSubmit: 'Send invite',
        alreadyMember: 'Already a member', notOwner: 'Only the owner can invite', inviteFailed: 'Could not send',
        inviteCreated: 'Invite created', copyLinkIntro: 'copy it', copyLink: 'Copy link', copied: 'Copied',
      },
    },
  },
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['type', 'label', 'disabled'],
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const mountDialog = () =>
  mount(InviteMemberDialog, { props: { visible: true, tenantId: 't-1' }, global: { plugins: [i18n], stubs } })

describe('InviteMemberDialog (uc.spaces.add-member, web)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('invites a trimmed + lowercased email, then emits invited and shows the copy-link view (stays open)', async () => {
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('  Partner@Example.COM  ')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(inviteMember).toHaveBeenCalledWith('t-1', 'partner@example.com')
    expect(wrapper.emitted('invited')).toHaveLength(1)
    // The dialog stays open and shows the invite link (built with the returned secret) for the owner to copy.
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(wrapper.find('.invite-link-input').exists()).toBe(true)
    expect((wrapper.find('.invite-link-input').element as HTMLInputElement).value).toContain('/tenant/accept#tok123')
  })

  it('closes on Done from the copy-link view', async () => {
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('partner@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    const done = wrapper.findAll('button').find((b) => b.text() === 'Done')!
    await done.trigger('click')
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('does not submit an empty email', async () => {
    const wrapper = mountDialog()
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(inviteMember).not.toHaveBeenCalled()
    expect(wrapper.emitted('invited')).toBeUndefined()
  })

  it('surfaces already_member distinctly and stays open', async () => {
    vi.mocked(inviteMember).mockRejectedValueOnce(new Error('409'))
    vi.mocked(memberError).mockResolvedValueOnce({ status: 409, code: 'already_member' })
    const wrapper = mountDialog()
    await wrapper.find('input').setValue('dup@example.com')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('Already a member')
    expect(wrapper.emitted('invited')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })
})
