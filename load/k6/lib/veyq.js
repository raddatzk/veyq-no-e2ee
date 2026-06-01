import http from 'k6/http'
import { group } from 'k6'
import { config } from './config.js'
import { absoluteUrl, acceptStatus, del, encoded, expectStatus, get, post, put, putBytes } from './http.js'
import { tinyPng, tinyPngMetadata } from './media.js'

export function createRunData() {
  const runName = `k6-run-${config.runId}`
  const root = group('setup create root memory', () => {
    const response = post(`/api/${encoded(config.tenant)}/memories`, {
      folderPath: '/',
      name: runName,
      displayName: `k6 run ${config.runId}`,
      startDate: '2026-05-01',
      endDate: '2026-05-01',
      scope: { type: 'TENANT' },
    }, { name: 'POST /api/{tenant}/memories' })
    expectStatus(response, 200, 'create root memory')
    return response.json()
  })

  const child = group('setup create child memory', () => {
    const response = post(`/api/${encoded(config.tenant)}/memories`, {
      parentId: root.id,
      name: 'child',
      displayName: 'k6 child',
      startDate: '2026-05-01',
      endDate: '2026-05-01',
    }, { name: 'POST /api/{tenant}/memories child' })
    expectStatus(response, 200, 'create child memory')
    return response.json()
  })

  expectStatus(put(`/api/${encoded(config.tenant)}/memories/${encoded(root.id)}/journal`, {
    markdown: `# k6 run ${config.runId}\n\nInitial journal body.`,
  }, { name: 'PUT /api/{tenant}/memories/{memoryId}/journal setup' }), 200, 'setup journal')

  const viewerShare = createShare(root.id, 'VIEWER', 'k6 viewer')
  const managerShare = createShare(root.id, 'MANAGER', 'k6 manager')
  const uploaderShare = createShare(root.id, 'UPLOADER', 'k6 uploader')

  return {
    runId: config.runId,
    root,
    child,
    shares: {
      viewer: viewerShare,
      manager: managerShare,
      uploader: uploaderShare,
    },
  }
}

export function cleanupRunData(data) {
  if (config.keepTestData || !data?.root?.id) return
  const response = del(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}`, null, {
    name: 'DELETE /api/{tenant}/memories/{memoryId}',
  })
  acceptStatus(response, [204, 404], 'cleanup root memory')
  if (response.status !== 204 && response.status !== 404) {
    console.error(`Cleanup failed for run ${data.runId}, root memory ${data.root.id}: ${response.status} ${response.body}`)
  }
}

export function browsePrivateMemory(data) {
  group('private browse', () => {
    expectStatus(get(`/api/${encoded(config.tenant)}/memory-navigation`, { name: 'GET /api/{tenant}/memory-navigation' }), 200, 'memory navigation')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}`, { name: 'GET /api/{tenant}/memories/{memoryId}' }), 200, 'get memory')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/header`, { name: 'GET /api/{tenant}/memories/{memoryId}/header' }), 200, 'get memory header')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/children`, { name: 'GET /api/{tenant}/memories/{memoryId}/children' }), 200, 'list children')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/media`, { name: 'GET /api/{tenant}/memories/{memoryId}/media' }), 200, 'list media')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/media-buckets`, { name: 'GET /api/{tenant}/memories/{memoryId}/media-buckets' }), 200, 'list buckets')
    expectStatus(get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/journal`, { name: 'GET /api/{tenant}/memories/{memoryId}/journal' }), 200, 'get journal')
  })
}

export function editJournal(data) {
  group('journal edit', () => {
    const markdown = `# k6 run ${data.runId}\n\nVU ${__VU} iteration ${__ITER} at ${new Date().toISOString()}.`
    expectStatus(put(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/journal`, { markdown }, {
      name: 'PUT /api/{tenant}/memories/{memoryId}/journal',
    }), 200, 'update journal')

    const liveSessionResponse = post(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/journal/live-session`, {}, {
      name: 'POST /api/{tenant}/memories/{memoryId}/journal/live-session',
    })
    expectStatus(liveSessionResponse, 200, 'create live journal session')
    const liveSession = liveSessionResponse.json()

    expectStatus(post('/api/collaboration/journal/session/validate', {
      token: liveSession.token,
      documentId: liveSession.documentId,
    }, { name: 'POST /api/collaboration/journal/session/validate' }), 200, 'validate live journal session')

    expectStatus(put('/api/collaboration/journal/session/snapshot', {
      token: liveSession.token,
      documentId: liveSession.documentId,
      markdown,
    }, { name: 'PUT /api/collaboration/journal/session/snapshot' }), 200, 'persist live journal snapshot')
  })
}

export function uploadPrivateMedia(data) {
  group('private media upload', () => {
    const media = uploadMemoryMedia(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/media/uploads`, data.runId)
    const mediaList = get(`/api/${encoded(config.tenant)}/memories/${encoded(data.root.id)}/media`, {
      name: 'GET /api/{tenant}/memories/{memoryId}/media after upload',
    })
    expectStatus(mediaList, 200, 'list media after upload')
    requestOptionalMediaDerivatives(media)
  })
}

export function browsePublicShare(data) {
  group('public share browse', () => {
    const jar = new http.CookieJar()
    expectStatus(http.get(absoluteUrl(`/api/public-shares/${encoded(data.shares.viewer.id)}`), {
      jar,
      tags: { name: 'GET /api/public-shares/{shareId}', type: 'api' },
    }), 200, 'get public share')
    expectStatus(http.get(absoluteUrl(`/api/public-shares/${encoded(data.shares.viewer.id)}/tree`), {
      jar,
      tags: { name: 'GET /api/public-shares/{shareId}/tree', type: 'api' },
    }), 200, 'get public share tree')
    expectStatus(http.get(absoluteUrl(`/api/public-shares/${encoded(data.shares.viewer.id)}/memories/${encoded(data.root.id)}/header`), {
      jar,
      tags: { name: 'GET /api/public-shares/{shareId}/memories/{memoryId}/header', type: 'api' },
    }), 200, 'get public share header')
    expectStatus(http.get(absoluteUrl(`/api/public-shares/${encoded(data.shares.viewer.id)}/memories/${encoded(data.root.id)}/journal`), {
      jar,
      tags: { name: 'GET /api/public-shares/{shareId}/memories/{memoryId}/journal', type: 'api' },
    }), 200, 'get public share journal')
    expectStatus(http.get(absoluteUrl(`/api/public-shares/${encoded(data.shares.viewer.id)}/memories/${encoded(data.root.id)}/media`), {
      jar,
      tags: { name: 'GET /api/public-shares/{shareId}/memories/{memoryId}/media', type: 'api' },
    }), 200, 'get public share media')
  })
}

export function uploadPublicMedia(data) {
  group('public uploader media upload', () => {
    const jar = new http.CookieJar()
    const loginResponse = http.post(
      absoluteUrl(`/api/public-shares/${encoded(data.shares.uploader.id)}/login`),
      JSON.stringify({ displayName: `k6 uploader ${__VU || 0}` }),
      {
        jar,
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'POST /api/public-shares/{shareId}/login', type: 'api' },
      },
    )
    acceptStatus(loginResponse, [200, 204], 'public uploader login')

    const bytes = tinyPng()
    const metadata = tinyPngMetadata(data.runId)
    const createResponse = http.post(
      absoluteUrl(`/api/public-shares/${encoded(data.shares.uploader.id)}/media/uploads`),
      JSON.stringify(metadata),
      {
        jar,
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'POST /api/public-shares/{shareId}/media/uploads', type: 'api' },
      },
    )
    expectStatus(createResponse, 200, 'create public upload')
    const upload = createResponse.json()

    expectStatus(http.put(
      absoluteUrl(`/api/public-shares/${encoded(data.shares.uploader.id)}/media/uploads/${encoded(upload.uploadId)}/chunks/0`),
      bytes,
      {
        jar,
        headers: { 'Content-Type': 'application/octet-stream' },
        tags: { name: 'PUT /api/public-shares/{shareId}/media/uploads/{uploadId}/chunks/{chunkIndex}', type: 'api' },
      },
    ), 204, 'upload public chunk')

    expectStatus(http.post(
      absoluteUrl(`/api/public-shares/${encoded(data.shares.uploader.id)}/media/uploads/${encoded(upload.uploadId)}/complete`),
      null,
      {
        jar,
        tags: { name: 'POST /api/public-shares/{shareId}/media/uploads/{uploadId}/complete', type: 'api' },
      },
    ), 200, 'complete public upload')
  })
}

function createShare(memoryId, role, name) {
  const response = post(`/api/${encoded(config.tenant)}/memories/${encoded(memoryId)}/shares`, {
    name,
    role,
  }, { name: `POST /api/{tenant}/memories/{memoryId}/shares ${role}` })
  expectStatus(response, 200, `create ${role} share`)
  return response.json()
}

function uploadMemoryMedia(uploadBasePath, runId) {
  const bytes = tinyPng()
  const metadata = tinyPngMetadata(runId)
  const createResponse = post(uploadBasePath, metadata, { name: 'POST media upload session' })
  expectStatus(createResponse, 200, 'create media upload')
  const upload = createResponse.json()

  expectStatus(putBytes(`${uploadBasePath}/${encoded(upload.uploadId)}/chunks/0`, bytes, {
    name: 'PUT media upload chunk',
  }), 204, 'upload media chunk')

  const completeResponse = post(`${uploadBasePath}/${encoded(upload.uploadId)}/complete`, null, {
    name: 'POST media upload complete',
  })
  expectStatus(completeResponse, 200, 'complete media upload')
  return completeResponse.json()
}

function requestOptionalMediaDerivatives(media) {
  ;['thumbnailUrl', 'previewUrl', 'posterUrl'].forEach((key) => {
    if (!media || !media[key]) return
    const response = get(media[key], { name: `GET media ${key}` })
    acceptStatus(response, [200, 202, 404], `media ${key}`)
  })
}
