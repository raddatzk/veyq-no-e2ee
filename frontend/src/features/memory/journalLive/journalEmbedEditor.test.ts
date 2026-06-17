import { afterEach, describe, expect, it } from 'vitest'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { journalEmbedEditing } from './journalEmbedEditor'

let view: EditorView | null = null
afterEach(() => { view?.destroy(); view = null })

function makeView(doc: string): EditorView {
  const parent = document.createElement('div')
  document.body.appendChild(parent)
  return new EditorView({
    parent,
    state: EditorState.create({
      doc,
      extensions: [journalEmbedEditing({
        chipText: (e) => `${e.kind}:${e.ids.join(',')}`,
        typeLabels: () => ({ media: 'Media', track: 'Track', chapter: 'Chapter' }),
        onInsert: () => {},
        onEdit: () => {},
      })],
    }),
  })
}

const backspace = (v: EditorView) => v.contentDOM.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true, cancelable: true }))

describe('journalEmbedEditing', () => {
  it('renders an atomic chip for an embed token', () => {
    view = makeView('Hi ::media:a,b Trip')
    const chip = view.dom.querySelector('.journal-chip')
    expect(chip).not.toBeNull()
    expect(chip!.textContent).toContain('media:a,b')
  })

  it('takes two Backspace presses at the token end to delete the whole embed', () => {
    view = makeView('::media:a Photo')
    const len = view.state.doc.length
    view.dispatch({ selection: { anchor: len } })

    backspace(view) // first press: arm, no deletion
    expect(view.state.doc.toString()).toBe('::media:a Photo')
    expect(view.dom.querySelector('.journal-chip-pending')).not.toBeNull()

    backspace(view) // second press: delete the token
    expect(view.state.doc.toString()).toBe('')
  })

  it('disarms a pending delete when the cursor moves', () => {
    view = makeView('::track:t1 Route')
    const len = view.state.doc.length
    view.dispatch({ selection: { anchor: len } })
    backspace(view)
    expect(view.dom.querySelector('.journal-chip-pending')).not.toBeNull()
    view.dispatch({ selection: { anchor: 0 } }) // move cursor → disarm
    expect(view.dom.querySelector('.journal-chip-pending')).toBeNull()
    backspace(view) // at position 0 there's no token end → no deletion
    expect(view.state.doc.toString()).toBe('::track:t1 Route')
  })
})
