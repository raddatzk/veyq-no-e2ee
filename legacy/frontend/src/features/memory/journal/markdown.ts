// A deliberately small, safe Markdown→HTML renderer for journal bodies: headings, bold/italic/inline-code,
// links, bullet/ordered lists, and paragraphs. Everything is HTML-escaped first, so no raw HTML or script
// can pass through. Rich embed tokens (::media / ::track …) are out of scope here — deferred with the rest
// of the journal media-embed feature; for now an embed line just renders as its literal text.

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** Inline spans on already-escaped text: links, bold, italic, inline code. */
function renderInline(escaped: string): string {
  return escaped
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, (_m, text, url) => `<a href="${url}" rel="noopener noreferrer" target="_blank">${text}</a>`)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
}

/** Render a safe Markdown subset to an HTML string. */
export function renderMarkdown(markdown: string): string {
  const lines = markdown.replace(/\r\n/g, '\n').split('\n')
  const html: string[] = []
  let list: 'ul' | 'ol' | null = null
  let paragraph: string[] = []

  const flushList = () => { if (list) { html.push(`</${list}>`); list = null } }
  const flushParagraph = () => {
    if (paragraph.length) { html.push(`<p>${renderInline(paragraph.join('<br>'))}</p>`); paragraph = [] }
  }

  for (const raw of lines) {
    const line = raw.trimEnd()
    const heading = /^(#{1,3})\s+(.*)$/.exec(line)
    const bullet = /^[-*]\s+(.*)$/.exec(line)
    const ordered = /^\d+\.\s+(.*)$/.exec(line)

    if (line.trim() === '') { flushParagraph(); flushList(); continue }
    if (heading) {
      flushParagraph(); flushList()
      const level = heading[1].length
      html.push(`<h${level}>${renderInline(escapeHtml(heading[2]))}</h${level}>`)
    } else if (bullet || ordered) {
      flushParagraph()
      const want = bullet ? 'ul' : 'ol'
      if (list !== want) { flushList(); html.push(`<${want}>`); list = want }
      html.push(`<li>${renderInline(escapeHtml((bullet ?? ordered)![1]))}</li>`)
    } else {
      flushList()
      paragraph.push(escapeHtml(line))
    }
  }
  flushParagraph(); flushList()
  return html.join('')
}
