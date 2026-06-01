import type { Completion, CompletionContext, CompletionResult } from '@codemirror/autocomplete'
import type { JournalEmbedKind, JournalEmbedRange, MediaInsertRange } from './journalMediaMarkdown'

export type JournalEmbedTypeCompletionConfig = {
  media: boolean
  chapter: boolean
  playlist: boolean
  track: boolean
  labels: Record<JournalEmbedKind, string>
  onMedia: (range: MediaInsertRange) => void
  onEmbed: (range: JournalEmbedRange) => void
}

export function createJournalEmbedTypeCompletionSource(config: JournalEmbedTypeCompletionConfig) {
  return (context: CompletionContext): CompletionResult | null => {
    const match = context.matchBefore(/::[A-Za-z]*$/)
    if (!match || (match.from === match.to && !context.explicit)) return null
    const query = match.text.slice(2).toLocaleLowerCase()
    const options = journalEmbedTypeCompletionOptions(config, match.from, context.pos)
      .filter((option) => journalEmbedTypeCompletionMatches(option, query))
    if (options.length === 0) return null
    return {
      from: match.from,
      options,
      filter: false
    }
  }
}

export function journalEmbedTypeCompletionOptions(
  config: JournalEmbedTypeCompletionConfig,
  from: number,
  to: number
): Completion[] {
  const options: Completion[] = []
  if (config.media) {
    options.push({
      label: config.labels.media,
      type: 'veyq-media',
      apply: () => config.onMedia({ start: from, end: to })
    })
  }
  if (config.chapter) options.push(journalEmbedTypeCompletionOption(config, 'chapter', from, to))
  if (config.playlist) options.push(journalEmbedTypeCompletionOption(config, 'playlist', from, to))
  if (config.track) options.push(journalEmbedTypeCompletionOption(config, 'track', from, to))
  return options
}

function journalEmbedTypeCompletionOption(
  config: JournalEmbedTypeCompletionConfig,
  kind: Exclude<JournalEmbedKind, 'media'>,
  from: number,
  to: number
): Completion {
  return {
    label: config.labels[kind],
    type: `veyq-${kind}`,
    apply: () => config.onEmbed({ start: from, end: to, kind })
  }
}

function journalEmbedTypeCompletionMatches(option: Completion, query: string): boolean {
  if (!query) return true
  const syntax = option.type?.replace(/^veyq-/, '') ?? ''
  return option.label.toLocaleLowerCase().startsWith(query) || syntax.startsWith(query)
}
