import type { Completion, CompletionContext, CompletionResult } from '@codemirror/autocomplete'
import type { JournalEmbedKind, JournalEmbedRange, MediaInsertRange } from './journalMediaMarkdown'

export type JournalEmbedTypeCompletionConfig = {
  media: () => boolean
  chapter: () => boolean
  playlist: () => boolean
  track: () => boolean
  labels: () => Record<JournalEmbedKind, string>
  onMedia: (range: MediaInsertRange) => void
  onEmbed: (range: JournalEmbedRange) => void
}

export function createJournalEmbedTypeCompletionSource(config: JournalEmbedTypeCompletionConfig) {
  const optionCache = new Map<string, Completion>()
  return (context: CompletionContext): CompletionResult | null => {
    const match = context.matchBefore(/::[A-Za-z]*$/)
    if (!match || (match.from === match.to && !context.explicit)) return null
    const query = match.text.slice(2).toLocaleLowerCase()
    const options = journalEmbedTypeCompletionOptions(config, match.from, context.pos, optionCache)
      .filter((option) => journalEmbedTypeCompletionMatches(option, query))
    if (options.length === 0) return null
    return {
      from: match.from,
      options,
      filter: false,
      validFor: /^::[A-Za-z]*$/
    }
  }
}

export function journalEmbedTypeCompletionOptions(
  config: JournalEmbedTypeCompletionConfig,
  from: number,
  to: number,
  optionCache = new Map<string, Completion>()
): Completion[] {
  const options: Completion[] = []
  if (config.media()) {
    options.push(journalEmbedTypeCompletionOption(config, 'media', from, to, optionCache))
  }
  if (config.chapter()) options.push(journalEmbedTypeCompletionOption(config, 'chapter', from, to, optionCache))
  if (config.playlist()) options.push(journalEmbedTypeCompletionOption(config, 'playlist', from, to, optionCache))
  if (config.track()) options.push(journalEmbedTypeCompletionOption(config, 'track', from, to, optionCache))
  return options
}

function journalEmbedTypeCompletionOption(
  config: JournalEmbedTypeCompletionConfig,
  kind: JournalEmbedKind,
  from: number,
  to: number,
  optionCache: Map<string, Completion>
): Completion {
  const labels = config.labels()
  const key = `${kind}:${from}:${to}:${labels[kind]}`
  const cached = optionCache.get(key)
  if (cached) return cached
  const option: Completion = {
    label: labels[kind],
    type: `veyq-${kind}`,
    apply: () => {
      if (kind === 'media') {
        config.onMedia({ start: from, end: to })
        return
      }
      config.onEmbed({ start: from, end: to, kind })
    }
  }
  optionCache.set(key, option)
  return option
}

function journalEmbedTypeCompletionMatches(option: Completion, query: string): boolean {
  if (!query) return true
  const syntax = option.type?.replace(/^veyq-/, '') ?? ''
  return option.label.toLocaleLowerCase().startsWith(query) || syntax.startsWith(query)
}
