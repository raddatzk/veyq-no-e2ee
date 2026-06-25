import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const require = createRequire(import.meta.url)

function loadYamlParser() {
  try {
    return require(require.resolve('js-yaml', { paths: [path.join(root, 'frontend')] }))
  } catch (error) {
    if (error.code === 'MODULE_NOT_FOUND') {
      console.error(
        [
          'Feature coverage validation needs frontend dependencies because js-yaml is declared there.',
          'Run `cd frontend && npm ci` before `node scripts/validate-feature-coverage.mjs`.',
        ].join('\n'),
      )
      process.exit(1)
    }
    throw error
  }
}

const yaml = loadYamlParser()
const coverageDir = path.join(root, 'docs/wiki/coverage')
const coverageFeaturesDir = path.join(coverageDir, 'features')
const wikiFeaturesDir = path.join(root, 'docs/wiki/features')
const legacyFeatureDir = path.join(root, 'docs/product/features')
const reverseAuditPath = path.join(root, 'docs/product/test-feature-reverse-audit.md')

const allowedCoverage = {
  backend: new Set(['unit', 'integration']),
  web: new Set(['unit', 'e2e']),
  android: new Set(['unit', 'e2e']),
  apple: new Set(['unit', 'e2e']),
}

const allowedDocumentTypes = new Set([
  'feature',
  'useCase',
  'crosscutting',
  'decision',
  'overview',
  'api',
  'entryPoint',
  'actor',
  'designNote',
])

const errors = []
const coverageRefs = new Set()

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

function extractTestNames(file) {
  const source = read(file)
  const tests = []
  let match

  if (/\.kt$/.test(file)) {
    const kotlinAnnotatedTest = /@Test(?:\s*\([^)]*\))?\s+fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(/g
    while ((match = kotlinAnnotatedTest.exec(source))) tests.push(match[1] || match[2])
    return tests
  }

  if (/\.swift$/.test(file)) {
    const swiftTest = /\bfunc\s+(test[A-Za-z0-9_]+)\s*\(/g
    while ((match = swiftTest.exec(source))) tests.push(match[1])
    return tests
  }

  const tsTest = /\b(?:it|test)\s*\(\s*(['"`])((?:\\.|(?!\1)[\s\S])*?)\1/g
  while ((match = tsTest.exec(source))) {
    tests.push(match[2].replace(/\$\{[\s\S]*?\}/g, '${...}'))
  }

  return tests
}

function collectDeleteCandidates() {
  if (!fs.existsSync(reverseAuditPath)) return new Set()
  const text = read(reverseAuditPath)
  return new Set([...text.matchAll(/`([^`]+::[^`]+)`/g)].map((match) => match[1]))
}

function checkRef(ownerPath, label, refPath) {
  if (typeof refPath !== 'string' || refPath.trim() === '') {
    errors.push(`${ownerPath}: ${label} has empty ref`)
    return
  }
  if (!fs.existsSync(path.join(root, refPath))) {
    errors.push(`${ownerPath}: missing ${label} ${refPath}`)
  }
}

const coverageFiles = walk(coverageFeaturesDir, (file) => /\/use-cases\/uc\.[^/]+\.yaml$/.test(file)).sort()

if (coverageFiles.length === 0) {
  errors.push(`missing use-case coverage files in ${rel(coverageFeaturesDir)}`)
}

for (const file of coverageFiles) {
  const coveragePath = rel(file)
  const coverageFeature = path.basename(path.dirname(path.dirname(file)))
  const expectedId = path.basename(file, '.yaml')
  const expectedUseCaseRef = `docs/wiki/features/${coverageFeature}/use-cases/${expectedId}.md`
  const expectedFeatureRef = `docs/wiki/features/${coverageFeature}/_index.md`
  const source = read(file)
  if (source.includes('coverageSeeds')) {
    errors.push(`${coveragePath}: contains retired coverageSeeds field`)
  }

  let doc
  try {
    doc = yaml.load(source)
  } catch (error) {
    errors.push(`${coveragePath}: YAML parse failed: ${error.message}`)
    continue
  }

  if (!doc || typeof doc !== 'object') {
    errors.push(`${coveragePath}: must contain a YAML object`)
    continue
  }

  for (const key of ['id', 'title', 'status', 'documents', 'acceptanceCriteria']) {
    if (!doc[key]) errors.push(`${coveragePath}: missing ${key}`)
  }

  if (doc.id && doc.id !== expectedId) {
    errors.push(`${coveragePath}: id "${doc.id}" must match filename "${expectedId}"`)
  }

  const idFeature = (doc.id || expectedId).split('.')[1]
  if (idFeature && idFeature !== coverageFeature) {
    errors.push(`${coveragePath}: id feature "${idFeature}" must match coverage feature folder "${coverageFeature}"`)
  }

  const useCasePath = path.join(root, expectedUseCaseRef)
  if (!fs.existsSync(useCasePath)) {
    errors.push(`${coveragePath}: missing mirrored use-case ${expectedUseCaseRef}`)
  } else {
    const useCaseId = read(useCasePath).match(/^- \*\*ID:\*\*\s*(\S+)\s*$/m)?.[1]
    if (useCaseId !== expectedId) {
      errors.push(`${coveragePath}: mirrored use-case ID "${useCaseId || '<missing>'}" must match "${expectedId}"`)
    }
  }

  const documentRefs = new Map()
  for (const artifact of doc.documents || []) {
    if (artifact?.type && artifact?.ref) {
      const refs = documentRefs.get(artifact.type) || new Set()
      refs.add(artifact.ref)
      documentRefs.set(artifact.type, refs)
    }
  }

  if (!documentRefs.get('feature')?.has(expectedFeatureRef)) {
    errors.push(`${coveragePath}: documents must include feature ref ${expectedFeatureRef}`)
  }

  if (!documentRefs.get('useCase')?.has(expectedUseCaseRef)) {
    errors.push(`${coveragePath}: documents must include useCase ref ${expectedUseCaseRef}`)
  }

  for (const artifact of doc.documents || []) {
    if (!artifact || typeof artifact !== 'object') {
      errors.push(`${coveragePath}: documents entries must be objects`)
      continue
    }
    if (!allowedDocumentTypes.has(artifact.type)) {
      errors.push(`${coveragePath}: invalid document type ${artifact.type}`)
    }
    checkRef(coveragePath, `${artifact.type || 'document'} document`, artifact.ref)
  }

  if (!Array.isArray(doc.acceptanceCriteria) || doc.acceptanceCriteria.length === 0) {
    errors.push(`${coveragePath}: missing acceptanceCriteria`)
    continue
  }

  for (const criterion of doc.acceptanceCriteria) {
    if (!criterion || typeof criterion !== 'object') {
      errors.push(`${coveragePath}: acceptanceCriteria entries must be objects`)
      continue
    }
    if (!criterion.id) errors.push(`${coveragePath}: acceptance criterion missing id`)
    if (!criterion.statement) errors.push(`${coveragePath}: ${criterion.id || '<missing id>'} missing statement`)

    const hasCoverage = criterion.coverage && Object.keys(criterion.coverage).length > 0
    const hasGap = Array.isArray(criterion.gaps) && criterion.gaps.length > 0

    if (doc.status === 'implemented' && !hasCoverage && !hasGap) {
      errors.push(`${coveragePath}: ${criterion.id} has neither coverage nor gap`)
    }

    for (const gap of criterion.gaps || []) {
      if (!gap.reason || !gap.followUp) {
        errors.push(`${coveragePath}: ${criterion.id} gap must include reason and followUp`)
      }
    }

    for (const [area, levels] of Object.entries(criterion.coverage || {})) {
      if (!allowedCoverage[area]) {
        errors.push(`${coveragePath}: ${criterion.id} invalid coverage area ${area}`)
        continue
      }

      for (const [level, items] of Object.entries(levels || {})) {
        if (!allowedCoverage[area].has(level)) {
          errors.push(`${coveragePath}: ${criterion.id} invalid coverage level ${area}.${level}`)
        }

        if (!Array.isArray(items) || items.length === 0) {
          errors.push(`${coveragePath}: ${criterion.id} ${area}.${level} must contain coverage items`)
          continue
        }

        for (const item of items) {
          for (const key of ['testName', 'kind', 'ref']) {
            if (!item[key]) errors.push(`${coveragePath}: ${criterion.id} coverage item missing ${key}`)
          }

          if (item.kind && !['positive', 'negative'].includes(item.kind)) {
            errors.push(`${coveragePath}: ${criterion.id} invalid coverage kind ${item.kind}`)
          }

          if (!item.ref) continue
          const refPath = path.join(root, item.ref)
          if (!fs.existsSync(refPath)) {
            errors.push(`${coveragePath}: ${criterion.id} missing ref ${item.ref}`)
            continue
          }

          const refSource = read(refPath)
          if (item.testName && !refSource.includes(item.testName)) {
            errors.push(`${coveragePath}: ${criterion.id} testName not found in ${item.ref}: ${item.testName}`)
          }

          coverageRefs.add(`${item.ref}::${item.testName}`)
        }
      }
    }
  }
}

const deleteCandidates = collectDeleteCandidates()
const testFiles = [
  ...walk(path.join(root, 'backend/src/test'), (file) => /Test\.kt$/.test(file)),
  ...walk(path.join(root, 'frontend/src'), (file) => /\.test\.ts$/.test(file)),
  ...walk(path.join(root, 'android/core/src/test'), (file) => /Test\.kt$/.test(file)),
  ...walk(path.join(root, 'apple/Tests'), (file) => /\.swift$/.test(file)),
].sort()

let currentTestCount = 0
const unmapped = []

for (const file of testFiles) {
  const relative = rel(file)
  for (const testName of extractTestNames(file)) {
    currentTestCount += 1
    const key = `${relative}::${testName}`
    if (!coverageRefs.has(key) && !deleteCandidates.has(key)) unmapped.push(key)
  }
}

if (unmapped.length > 0) {
  errors.push(`unmapped current tests:\n${unmapped.join('\n')}`)
}

if (fs.existsSync(legacyFeatureDir) && coverageFiles.length > 0) {
  const legacyFiles = walk(legacyFeatureDir, (file) => file.endsWith('.yaml'))
  if (legacyFiles.length > 0) {
    console.warn(
      `Note: ${legacyFiles.length} legacy feature files remain in ${rel(legacyFeatureDir)}; active coverage is ${rel(coverageDir)}.`,
    )
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'))
  process.exit(1)
}

console.log(
  `Validated ${coverageFiles.length} use-case coverage files, ${coverageRefs.size} unique coverage refs, ${currentTestCount} current tests, 0 unmapped current tests.`,
)
