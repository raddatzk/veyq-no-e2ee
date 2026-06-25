import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConfirmationDialog from './ConfirmationDialog.vue'

describe('ConfirmationDialog', () => {
  it('emits confirm from the submit action', async () => {
    const wrapper = mountDialog()

    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('blocks duplicate confirm and cancel while loading', async () => {
    const wrapper = mountDialog({ loading: true })

    await wrapper.find('form').trigger('submit')
    await buttonByText(wrapper, 'Abbrechen').trigger('click')

    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(buttonByText(wrapper, 'Abbrechen').attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, 'Löschen').attributes('data-loading')).toBe('true')
  })

  it('keeps the dialog open when the shell tries to close during loading', async () => {
    const wrapper = mountDialog({ loading: true })

    await wrapper.find('[data-testid="dialog-close"]').trigger('click')

    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('allows cancel after loading ends', async () => {
    const wrapper = mountDialog({ loading: true })
    await wrapper.setProps({ loading: false })
    await flushPromises()

    await buttonByText(wrapper, 'Abbrechen').trigger('click')

    expect(wrapper.emitted('update:visible')).toEqual([[false]])
  })

  it('disables confirm when confirmation requirements are not met', async () => {
    const wrapper = mountDialog({ confirmDisabled: true })

    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(buttonByText(wrapper, 'Löschen').attributes('disabled')).toBeDefined()
  })
})

function mountDialog(props: Partial<InstanceType<typeof ConfirmationDialog>['$props']> = {}) {
  return mount(ConfirmationDialog, {
    props: {
      visible: true,
      title: 'Bestätigen',
      confirmLabel: 'Löschen',
      confirmSeverity: 'danger',
      ...props
    },
    slots: {
      default: '<p>Wirklich löschen?</p>'
    },
    global: {
      stubs: {
        Button: {
          template:
            '<button :type="type || \'button\'" :disabled="disabled" :data-loading="String(loading)" @click="$emit(\'click\', $event)">{{ label }}</button>',
          props: ['disabled', 'label', 'loading', 'type'],
          emits: ['click']
        },
        Dialog: {
          template:
            '<section v-if="visible"><button data-testid="dialog-close" type="button" @click="$emit(\'update:visible\', false)">x</button><slot /></section>',
          props: ['visible'],
          emits: ['update:visible']
        }
      }
    }
  })
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === text)
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}
