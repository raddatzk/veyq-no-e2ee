import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { execFileSync } from 'node:child_process'

// Render inline ```plantuml blocks into SVG images for the GitHub Wiki.
// Operates IN PLACE on a copy of the wiki tree (e.g. the cloned wiki, or a preview copy) so the
// repository source keeps its readable inline blocks. For each ```plantuml block it:
//   1. writes the source to <dir>/diagrams/auto/<hash>.puml,
//   2. replaces the block in the .md with an image link to the rendered SVG,
//   3. renders all collected .puml to SVG via `plantuml` (override with PLANTUML_CMD).
// Usage: node scripts/render-wiki-diagrams.mjs <dir>

const dir = process.argv[2]
if (!dir) {
  console.error('usage: node scripts/render-wiki-diagrams.mjs <dir>')
  process.exit(1)
}

const autoDir = path.join(dir, 'diagrams', 'auto')

function walk(d, out = []) {
  for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
    const full = path.join(d, entry.name)
    if (entry.isDirectory()) walk(full, out)
    else if (full.endsWith('.md')) out.push(full)
  }
  return out
}

const pumls = []

for (const md of walk(dir)) {
  let changed = false
  const src = fs.readFileSync(md, 'utf8')
  const next = src.replace(/```plantuml\s*\n([\s\S]*?)\n```/g, (_match, body) => {
    const source = body.trim() + '\n'
    const hash = crypto.createHash('sha1').update(source).digest('hex').slice(0, 12)
    fs.mkdirSync(autoDir, { recursive: true })
    fs.writeFileSync(path.join(autoDir, `${hash}.puml`), source)
    pumls.push(`${hash}.puml`)
    const titleMatch = source.match(/^\s*title\s+(.+?)\s*$/m)
    const alt = titleMatch ? titleMatch[1] : 'diagram'
    const rel = path.relative(path.dirname(md), path.join(autoDir, `${hash}.svg`))
    changed = true
    return `![${alt}](${rel})`
  })
  if (changed) fs.writeFileSync(md, next)
}

if (pumls.length > 0) {
  const cmd = process.env.PLANTUML_CMD || 'plantuml'
  execFileSync(cmd, ['-tsvg', ...pumls.map((p) => path.join(autoDir, p))], { stdio: 'inherit' })
}

console.log(`Rendered ${pumls.length} inline PlantUML diagram(s) in ${dir}.`)
