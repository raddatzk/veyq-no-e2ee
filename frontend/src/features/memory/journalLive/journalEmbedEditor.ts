// CodeMirror glue for editing journal embeds: the raw `::media:…` tokens are shown as compact, atomic chips,
// `::` opens a type-picker via autocomplete, and Backspace/Delete at a chip boundary removes the whole token
// in a two-press (arm → confirm) gesture rather than nibbling characters. Token parsing/serialization lives in
// journalEmbed.ts; this module is the editor-only presentation + interaction layer. Ported from the legacy
// CodeMirror journal editor.
import { autocompletion, type Completion, type CompletionContext, type CompletionResult } from '@codemirror/autocomplete'
import { Decoration, type DecorationSet, EditorView, ViewPlugin, type ViewUpdate, WidgetType, keymap } from '@codemirror/view'
import { type Extension, RangeSetBuilder, StateEffect, Transaction } from '@codemirror/state'
import { type JournalEmbed, type JournalEmbedKind, journalEmbedMarkdown, parseJournalEmbeds } from '../journal/journalEmbed'

/** An embed token's position + parsed contents — what a picker needs to edit it in place. */
export type EmbedRange = { start: number; end: number; kind: JournalEmbedKind; ids: string[]; label?: string }

export type JournalEmbedEditorConfig = {
  /** The chip's display text for an embed (resolved against the memory's media/tracks/chapters). */
  chipText: (embed: JournalEmbed) => string
  /** The localized picker labels for each embed kind, shown in the `::` autocomplete list. */
  typeLabels: () => Record<JournalEmbedKind, string>
  /** Open a picker to insert a fresh embed of `kind`, replacing the `::`-trigger span [from,to). */
  onInsert: (kind: JournalEmbedKind, from: number, to: number) => void
  /** Open a picker to edit (or delete) the embed currently under the chip. */
  onEdit: (range: EmbedRange) => void
}

const toRange = (e: JournalEmbed): EmbedRange => ({ start: e.start, end: e.end, kind: e.kind, ids: e.ids, label: e.label })

/** Dispatch this effect to force chip re-rendering after async resolution data (track/chapter names) loads. */
export const refreshChips = StateEffect.define<void>()

/** Replace [from,to) with the given token text and drop the cursor right after it (used on insert + edit). */
export function replaceEmbed(view: EditorView, from: number, to: number, markdown: string): void {
  view.dispatch({
    changes: { from, to, insert: markdown },
    selection: { anchor: from + markdown.length },
    annotations: Transaction.userEvent.of('input.type'),
  })
  view.focus()
}

class EmbedChipWidget extends WidgetType {
  constructor(private readonly e: JournalEmbed, private readonly text: string, private readonly onEdit: (r: EmbedRange) => void) {
    super()
  }
  eq(other: EmbedChipWidget) {
    return this.e.start === other.e.start && this.e.end === other.e.end && this.text === other.text
  }
  toDOM() {
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = `journal-chip journal-chip-${this.e.kind}`
    btn.dataset.embedStart = String(this.e.start)
    btn.dataset.embedEnd = String(this.e.end)
    btn.title = this.text
    btn.addEventListener('mousedown', (ev) => ev.preventDefault())
    btn.addEventListener('click', (ev) => { ev.preventDefault(); ev.stopPropagation(); this.onEdit(toRange(this.e)) })
    const label = document.createElement('span')
    label.className = 'journal-chip-text'
    label.textContent = this.text
    const pencil = document.createElement('span')
    pencil.className = 'journal-chip-edit'
    pencil.textContent = '✎'
    pencil.setAttribute('aria-hidden', 'true')
    btn.append(label, pencil)
    return btn
  }
  ignoreEvent() { return false }
}

function buildChips(view: EditorView, config: JournalEmbedEditorConfig): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>()
  for (const embed of parseJournalEmbeds(view.state.doc.toString())) {
    builder.add(embed.start, embed.end, Decoration.replace({ widget: new EmbedChipWidget(embed, config.chipText(embed), config.onEdit) }))
  }
  return builder.finish()
}

/** The full embed-editing extension: atomic chips, `::` type autocomplete, and two-press atomic delete. */
export function journalEmbedEditing(config: JournalEmbedEditorConfig): Extension {
  // The chip currently "armed" for deletion (first Backspace/Delete press); a second press at the same
  // boundary removes it. Any cursor move or doc edit disarms it. Highlight is a DOM class on the chip button.
  let armed: { start: number; end: number } | null = null

  const disarm = (view: EditorView) => {
    if (!armed) return
    view.dom.querySelectorAll('.journal-chip-pending').forEach((el) => el.classList.remove('journal-chip-pending'))
    armed = null
  }
  const arm = (view: EditorView, r: { start: number; end: number }) => {
    disarm(view)
    armed = { start: r.start, end: r.end }
    view.dom.querySelector(`[data-embed-start="${r.start}"][data-embed-end="${r.end}"]`)?.classList.add('journal-chip-pending')
  }

  const deleteKey = (view: EditorView, dir: 'backward' | 'forward'): boolean => {
    const sel = view.state.selection.main
    if (!sel.empty) { disarm(view); return false }
    const pos = sel.head
    const hit = parseJournalEmbeds(view.state.doc.toString()).find((e) => (dir === 'backward' ? e.end === pos : e.start === pos))
    if (!hit) { disarm(view); return false }
    if (armed && armed.start === hit.start && armed.end === hit.end) {
      disarm(view)
      view.dispatch({
        changes: { from: hit.start, to: hit.end, insert: '' },
        selection: { anchor: hit.start },
        annotations: Transaction.userEvent.of(dir === 'backward' ? 'delete.backward' : 'delete.forward'),
      })
      return true
    }
    arm(view, hit)
    return true
  }

  const chips = ViewPlugin.fromClass(
    class {
      decorations: DecorationSet
      constructor(view: EditorView) { this.decorations = buildChips(view, config) }
      update(u: ViewUpdate) {
        // A selection move (without a doc edit) disarms a pending delete; only rebuild chips on doc/viewport change.
        if (u.selectionSet && !u.docChanged) disarm(u.view)
        const refresh = u.transactions.some((tr) => tr.effects.some((e) => e.is(refreshChips)))
        if (u.docChanged || u.viewportChanged || refresh) this.decorations = buildChips(u.view, config)
      }
    },
    { decorations: (p) => p.decorations },
  )

  const completion = autocompletion({
    override: [(ctx: CompletionContext): CompletionResult | null => {
      const m = ctx.matchBefore(/::[A-Za-z]*$/)
      if (!m || (m.from === m.to && !ctx.explicit)) return null
      const query = m.text.slice(2).toLowerCase()
      const labels = config.typeLabels()
      const kinds: JournalEmbedKind[] = ['media', 'track', 'chapter']
      const options: Completion[] = kinds
        .filter((k) => !query || k.startsWith(query) || labels[k].toLowerCase().startsWith(query))
        .map((k) => ({ label: labels[k], type: `veyq-${k}`, apply: () => config.onInsert(k, m.from, ctx.pos) }))
      return options.length ? { from: m.from, options, filter: false, validFor: /^::[A-Za-z]*$/ } : null
    }],
  })

  const keys = keymap.of([
    { key: 'Backspace', run: (v) => deleteKey(v, 'backward') },
    { key: 'Delete', run: (v) => deleteKey(v, 'forward') },
  ])

  return [chips, keys, completion]
}

/** Re-exported for callers that build the inserted/edited token text. */
export { journalEmbedMarkdown }
