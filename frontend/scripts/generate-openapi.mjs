import { execFileSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const specsRoot = resolve(root, '..', 'docs', 'api')
const outputRoot = resolve(root, 'src', 'api', 'generated')

const domains = [
  'auth',
  'setup',
  'users',
  'tenants',
  'groups',
  'library',
  'server',
  'settings',
  'explorer'
]

const generatorOptions = [
  'supportsES6=true',
  'withInterfaces=true',
  'useSingleRequestParameter=true',
  'typescriptThreePlus=true'
].join(',')

for (const domain of domains) {
  const output = resolve(outputRoot, domain)
  rmSync(output, { recursive: true, force: true })
  execFileSync(
    'npx',
    [
      'openapi-generator-cli',
      'generate',
      '-g',
      'typescript-fetch',
      '-i',
      resolve(specsRoot, `${domain}.openapi.yaml`),
      '-o',
      output,
      '--additional-properties',
      generatorOptions,
      '--global-property',
      'apiDocs=false,apiTests=false,modelDocs=false,modelTests=false',
      '--type-mappings',
      'DateTime=string,Date=string'
    ],
    { cwd: root, stdio: 'inherit' }
  )
}
