import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const specsRoot = resolve(root, '..', 'docs', 'api')
const outputRoot = resolve(root, 'src', 'api', 'generated')
const bundledSpec = resolve(root, 'build', 'generated', 'openapi', 'frontend.openapi.yaml')

const domains = [
  'common',
  'settings',
  'auth',
  'setup',
  'users',
  'tenants',
  'groups',
  'memories',
  'server',
  'frontend'
]

const generatorOptions = [
  'supportsES6=true',
  'withInterfaces=true',
  'useSingleRequestParameter=true',
  'typescriptThreePlus=true'
].join(',')

const bundled = {
  openapi: '3.1.0',
  info: {
    title: 'Veyq API',
    version: '0.1.0',
    description: 'Bundled frontend API contract generated from docs/api specs.'
  },
  servers: [{ url: '/' }],
  security: [{ accessTokenCookie: [] }],
  tags: [],
  paths: {},
  components: {}
}

for (const domain of domains) {
  const spec = rewriteExternalComponentRefs(readSpec(domain))

  mergeTags(spec.tags)
  Object.assign(bundled.paths, spec.paths ?? {})

  for (const componentKey of [
    'securitySchemes',
    'parameters',
    'requestBodies',
    'responses',
    'schemas',
    'examples'
  ]) {
    mergeComponents(componentKey, spec.components?.[componentKey])
  }
}

mkdirSync(dirname(bundledSpec), { recursive: true })
writeFileSync(bundledSpec, yaml.dump(bundled, { lineWidth: -1 }))

rmSync(outputRoot, { recursive: true, force: true })
execFileSync(
  'npx',
  [
    'openapi-generator-cli',
    'generate',
    '-g',
    'typescript-fetch',
    '-i',
    bundledSpec,
    '-o',
    outputRoot,
    '--additional-properties',
    generatorOptions,
    '--global-property',
    'apiDocs=false,apiTests=false,modelDocs=false,modelTests=false',
    '--type-mappings',
    'DateTime=string,Date=string'
  ],
  { cwd: root, stdio: 'inherit' }
)

function readSpec(domain) {
  return yaml.load(readFileSync(resolve(specsRoot, `${domain}.openapi.yaml`), 'utf8'))
}

function mergeTags(tags = []) {
  for (const tag of tags) {
    if (!bundled.tags.some((existing) => existing.name === tag.name)) {
      bundled.tags.push(tag)
    }
  }
}

function mergeComponents(componentKey, components) {
  if (!components) return

  bundled.components[componentKey] ??= {}
  for (const [name, component] of Object.entries(components)) {
    bundled.components[componentKey][name] ??= component
  }
}

function rewriteExternalComponentRefs(value) {
  if (typeof value === 'string') {
    return value
      .replace('./common.openapi.yaml#/components/schemas/', '#/components/schemas/')
      .replace('./common.openapi.yaml#/components/responses/', '#/components/responses/')
      .replace('./common.openapi.yaml#/components/parameters/', '#/components/parameters/')
      .replace('./tenants.openapi.yaml#/components/schemas/', '#/components/schemas/')
      .replace('./tenants.openapi.yaml#/components/responses/', '#/components/responses/')
      .replace('./tenants.openapi.yaml#/components/parameters/', '#/components/parameters/')
  }

  if (Array.isArray(value)) {
    return value.map(rewriteExternalComponentRefs)
  }

  if (value && typeof value === 'object') {
    const rewritten = Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, rewriteExternalComponentRefs(child)])
    )

    if (rewritten.type === 'array' && rewritten.$ref && !rewritten.items) {
      const { $ref, ...schema } = rewritten
      return { ...schema, items: { $ref } }
    }

    return rewritten
  }

  return value
}
