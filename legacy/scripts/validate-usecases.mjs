import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Deterministic validator for the Cockburn-lite use-cases under docs/wiki/use-cases.
// Markdown-based, no external dependencies. See docs/ai/documentation-guide.md.

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const featuresDir = path.join(root, 'docs/wiki/features')
const actorsPath = path.join(root, 'docs/wiki/actors.md')

const errors = []

function read(file) {
  return fs.readFileSync(file, 'utf8')
}

function rel(file) {
  return path.relative(root, file)
}

function walk(dir, predicate, out = []) {
  if (!fs.existsSync(dir)) return out
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, predicate, out)
    else if (predicate(full)) out.push(full)
  }
  return out
}

if (!fs.existsSync(actorsPath)) {
  console.error(`Missing actor registry: ${rel(actorsPath)}`)
  process.exit(1)
}

// Known actors = the `### <name>` headings in actors.md.
const knownActors = new Set(
  [...read(actorsPath).matchAll(/^###\s+(.+?)\s*$/gm)].map((m) => m[1]),
)

const REQUIRED_FIELDS = ['ID', 'Primary actor', 'Goal', 'Preconditions', 'Postconditions']
const REQUIRED_SECTIONS = ['## Main flow', '## Alternate / exception flows']

// Use-case files: features/<feature>/use-cases/uc.<feature>.<goal>.md
const files = walk(featuresDir, (f) => /\/use-cases\/uc\.[^/]+\.md$/.test(f)).sort()

const seenIds = new Map()

for (const file of files) {
  const relPath = rel(file)
  const source = read(file)
  const base = path.basename(file, '.md')

  for (const field of REQUIRED_FIELDS) {
    const escaped = field.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    // Allow an optional qualifier before the colon, e.g. "Postconditions (success):".
    if (!new RegExp(`^- \\*\\*${escaped}[^*\\n]*\\*\\*`, 'm').test(source)) {
      errors.push(`${relPath}: missing field "- **${field}:**"`)
    }
  }
  for (const section of REQUIRED_SECTIONS) {
    if (!source.includes(section)) {
      errors.push(`${relPath}: missing section "${section}"`)
    }
  }

  const idMatch = source.match(/^- \*\*ID:\*\*\s*(\S+)\s*$/m)
  if (idMatch) {
    const id = idMatch[1]
    if (id !== base) {
      errors.push(`${relPath}: ID "${id}" does not match filename "${base}"`)
    }
    // ID feature segment should match the containing feature folder (features/<feature>/use-cases/).
    const area = id.split('.')[1]
    const feature = path.basename(path.dirname(path.dirname(file)))
    if (area && feature !== area) {
      errors.push(`${relPath}: ID feature "${area}" does not match feature folder "${feature}"`)
    }
    if (seenIds.has(id)) {
      errors.push(`${relPath}: duplicate ID "${id}" (also in ${seenIds.get(id)})`)
    } else {
      seenIds.set(id, relPath)
    }
  }

  const actorMatch = source.match(/^- \*\*Primary actor:\*\*\s*(.+?)\s*$/m)
  if (actorMatch) {
    const actor = actorMatch[1]
    if (!knownActors.has(actor)) {
      errors.push(`${relPath}: unknown Primary actor "${actor}" (not in actors.md)`)
    }
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'))
  process.exit(1)
}

console.log(
  `Validated ${files.length} use-case files against ${knownActors.size} known actors, 0 errors.`,
)
