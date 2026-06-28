import { readFile } from 'node:fs/promises'

const packageJson = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
const packageLock = JSON.parse(await readFile(new URL('../package-lock.json', import.meta.url), 'utf8'))

const overrides = packageJson.overrides ?? {}
const packages = packageLock.packages ?? {}
const failures = []

const packageNameFromPath = (path) => {
  const marker = 'node_modules/'
  const index = path.lastIndexOf(marker)

  if (index === -1) {
    return null
  }

  const packagePath = path.slice(index + marker.length)
  const parts = packagePath.split('/')
  return packagePath.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]
}

for (const [name, expectedVersion] of Object.entries(overrides)) {
  if (typeof expectedVersion !== 'string') {
    failures.push(`${name}: nested overrides are not supported by this check`)
    continue
  }

  const matches = Object.entries(packages)
    .filter(([path]) => packageNameFromPath(path) === name)
    .map(([, metadata]) => metadata.version)

  if (matches.length === 0) {
    failures.push(`${name}: override is not used by package-lock.json anymore`)
    continue
  }

  const mismatches = [...new Set(matches.filter((version) => version !== expectedVersion))]
  if (mismatches.length > 0) {
    failures.push(`${name}: expected ${expectedVersion}, found ${mismatches.join(', ')}`)
  }
}

if (failures.length > 0) {
  console.error('Dependency override check failed:')
  for (const failure of failures) {
    console.error(`- ${failure}`)
  }
  console.error('Remove stale overrides or update the expected version in package.json.')
  process.exit(1)
}
