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
const featureDir = path.join(root, 'docs/product/features')
const reverseAuditPath = path.join(root, 'docs/product/test-feature-reverse-audit.md')
const capabilityPath = path.join(root, 'docs/product/capabilities.md')

const allowedCoverage = {
  backend: new Set(['unit', 'integration']),
  collaboration: new Set(['unit']),
  frontend: new Set(['unit', 'integration']),
  ios: new Set(['unit']),
  e2e: new Set(['browser', 'ios', 'android']),
}

const errors = []
const featureCoverageRefs = new Set()

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
    if (entry.isDirectory()) {
      walk(full, predicate, out)
    } else if (predicate(full)) {
      out.push(full)
    }
  }
  return out
}

function extractTestNames(file) {
  const source = read(file)
  const tests = []
  let match

  const kotlinTest = /fun\s+`([^`]+)`\s*\(/g
  while ((match = kotlinTest.exec(source))) {
    tests.push(match[1])
  }

  const tsTest = /\b(?:it|test)\s*\(\s*(['"`])((?:\\.|(?!\1)[\s\S])*?)\1/g
  while ((match = tsTest.exec(source))) {
    tests.push(match[2].replace(/\$\{[\s\S]*?\}/g, '${...}'))
  }

  const swiftTest = /\bfunc\s+(test[A-Za-z0-9_]+)\s*\(/g
  while ((match = swiftTest.exec(source))) {
    tests.push(match[1])
  }

  return tests
}

function collectDeleteCandidates() {
  if (!fs.existsSync(reverseAuditPath)) return new Set()
  const text = read(reverseAuditPath)
  return new Set([...text.matchAll(/`([^`]+::[^`]+)`/g)].map((match) => match[1]))
}

const capabilityDoc = read(capabilityPath)
const knownCapabilities = new Set(
  [...capabilityDoc.matchAll(/`([a-z0-9]+(?:[.-][a-z0-9]+)+)`/g)].map((match) => match[1]),
)

const featureFiles = walk(featureDir, (file) => file.endsWith('.yaml')).sort()
const catalogPath = path.join(featureDir, 'catalog.yml')

for (const file of featureFiles) {
  const featurePath = rel(file)
  const source = read(file)
  if (source.includes('coverageSeeds')) {
    errors.push(`${featurePath}: contains retired coverageSeeds field`)
  }

  let doc
  try {
    doc = yaml.load(source)
  } catch (error) {
    errors.push(`${featurePath}: YAML parse failed: ${error.message}`)
    continue
  }

  if (!Array.isArray(doc.acceptanceCriteria) || doc.acceptanceCriteria.length === 0) {
    errors.push(`${featurePath}: missing acceptanceCriteria`)
  }

  for (const capability of doc.capabilities || []) {
    if (!knownCapabilities.has(capability)) {
      errors.push(`${featurePath}: unknown capability ${capability}`)
    }
  }

  for (const criterion of doc.acceptanceCriteria || []) {
    const hasCoverage = criterion.coverage && Object.keys(criterion.coverage).length > 0
    const hasGap = Array.isArray(criterion.gaps) && criterion.gaps.length > 0

    if (doc.status === 'implemented' && !hasCoverage && !hasGap) {
      errors.push(`${featurePath}: ${criterion.id} has neither coverage nor gap`)
    }

    for (const [area, levels] of Object.entries(criterion.coverage || {})) {
      if (!allowedCoverage[area]) {
        errors.push(`${featurePath}: ${criterion.id} invalid coverage area ${area}`)
        continue
      }

      for (const [level, items] of Object.entries(levels || {})) {
        if (!allowedCoverage[area].has(level)) {
          errors.push(`${featurePath}: ${criterion.id} invalid coverage level ${area}.${level}`)
        }

        if (!Array.isArray(items) || items.length === 0) {
          errors.push(`${featurePath}: ${criterion.id} ${area}.${level} must contain coverage items`)
          continue
        }

        for (const item of items) {
          for (const key of ['testName', 'kind', 'ref']) {
            if (!item[key]) {
              errors.push(`${featurePath}: ${criterion.id} coverage item missing ${key}`)
            }
          }

          if (item.kind && !['positive', 'negative'].includes(item.kind)) {
            errors.push(`${featurePath}: ${criterion.id} invalid coverage kind ${item.kind}`)
          }

          if (!item.ref) continue
          const refPath = path.join(root, item.ref)
          if (!fs.existsSync(refPath)) {
            errors.push(`${featurePath}: ${criterion.id} missing ref ${item.ref}`)
            continue
          }

          const refSource = read(refPath)
          if (item.testName && !refSource.includes(item.testName)) {
            errors.push(`${featurePath}: ${criterion.id} testName not found in ${item.ref}: ${item.testName}`)
          }

          featureCoverageRefs.add(`${item.ref}::${item.testName}`)
        }
      }
    }

    if (criterion.attackPaths !== undefined) {
      errors.push(`${featurePath}: ${criterion.id} contains retired attackPaths field; derive pentest paths during run discovery instead`)
    }

    if (criterion.dynamicAttackPathSignals !== undefined) {
      errors.push(`${featurePath}: ${criterion.id} contains retired dynamicAttackPathSignals field; derive technical attack paths from run discovery instead`)
    }
  }

  if (doc.pentestScenarios !== undefined) {
    errors.push(`${featurePath}: contains retired top-level pentestScenarios field; derive pentest paths during run discovery instead`)
  }
}

if (fs.existsSync(catalogPath)) {
  let catalog
  try {
    catalog = yaml.load(read(catalogPath))
  } catch (error) {
    errors.push(`docs/product/features/catalog.yml: YAML parse failed: ${error.message}`)
  }

  if (catalog) {
    const knownFeatureIds = new Set()
    const listedFeatureIds = new Set()
    for (const file of featureFiles) {
      const doc = yaml.load(read(file))
      if (doc?.id) knownFeatureIds.add(doc.id)
    }

    for (const group of catalog.groups || []) {
      if (!group.id || !group.title || !group.intent) {
        errors.push(`docs/product/features/catalog.yml: group missing id title or intent`)
      }
      if (!Array.isArray(group.features) || group.features.length === 0) {
        errors.push(`docs/product/features/catalog.yml: group ${group.id || '<missing>'} missing features`)
        continue
      }
      for (const featureId of group.features) {
        if (!knownFeatureIds.has(featureId)) {
          errors.push(`docs/product/features/catalog.yml: unknown feature ${featureId}`)
        }
        if (listedFeatureIds.has(featureId)) {
          errors.push(`docs/product/features/catalog.yml: duplicate feature ${featureId}`)
        }
        listedFeatureIds.add(featureId)
      }
    }

    for (const featureId of knownFeatureIds) {
      if (!listedFeatureIds.has(featureId)) {
        errors.push(`docs/product/features/catalog.yml: missing feature ${featureId}`)
      }
    }
  }
}

const deleteCandidates = collectDeleteCandidates()
const testFiles = [
  ...walk(path.join(root, 'backend/src/test'), (file) => /Test\.kt$/.test(file)),
  ...walk(path.join(root, 'collaboration/test'), (file) => /\.test\.js$/.test(file)),
  ...walk(path.join(root, 'frontend/src'), (file) => /\.test\.ts$/.test(file)),
  ...walk(path.join(root, 'frontend/e2e'), (file) => /\.spec\.ts$/.test(file)),
].sort()

let currentTestCount = 0
const unmapped = []

for (const file of testFiles) {
  const relative = rel(file)
  for (const testName of extractTestNames(file)) {
    currentTestCount += 1
    const key = `${relative}::${testName}`
    if (!featureCoverageRefs.has(key) && !deleteCandidates.has(key)) {
      unmapped.push(key)
    }
  }
}

if (unmapped.length > 0) {
  errors.push(`unmapped current tests:\n${unmapped.join('\n')}`)
}

if (errors.length > 0) {
  console.error(errors.join('\n'))
  process.exit(1)
}

console.log(
  `Validated ${featureFiles.length} feature YAML files, ${featureCoverageRefs.size} unique coverage refs, ${currentTestCount} current tests, 0 unmapped current tests.`,
)
