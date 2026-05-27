import { expect, test } from '@playwright/test'
import { Buffer } from 'node:buffer'
import {
  geotaggedMedia,
  mockAuthenticatedOwnerWithoutTenants,
  mockMemoryDetailApi,
  mockPublicUploaderShareApi,
  mockPublicViewerShareApi,
  publicPlaylist,
  publicTrack,
  weddingMemory
} from './support/api'

test('V1 empty states guide first setup and empty Memories', async ({ page }) => {
  await mockAuthenticatedOwnerWithoutTenants(page)

  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Noch keinen Bereich für deine Memories' })).toBeVisible()
  await expect(page.getByText('Erstelle einen Bereich, um Memories mit Fotos, Videos und Kontext zu sammeln.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bereich erstellen' })).toBeVisible()

  const emptyMemory = {
    ...weddingMemory,
    mediaCount: 0,
    childCount: 0
  }
  await page.unroute('**://*/api/**')
  await mockMemoryDetailApi(page, {
    memory: emptyMemory,
    childMemories: [],
    media: [],
    journalMarkdown: ''
  })

  await page.goto(`/friends${emptyMemory.path}`)

  await expect(page.getByText('Starte diese Memory mit Fotos oder Videos.')).toBeVisible()
  await expect(page.getByRole('tabpanel', { name: 'Galerie' }).getByRole('button', { name: 'Hochladen' })).toBeVisible()

  await page.getByRole('tab', { name: 'Journal' }).click()
  await expect(page.getByText('Noch kein Journal für diese Memory.')).toBeVisible()
  await expect(page.locator('.memory-journal-empty').getByRole('button', { name: 'Journal starten' })).toBeVisible()

  await page.getByRole('tab', { name: 'Kapitel' }).click()
  await expect(page.getByRole('button', { name: 'Kapitel erstellen' })).toBeVisible()
})

test('V1 public viewer share stays gallery-only and shows share scope', async ({ page }) => {
  await mockPublicViewerShareApi(page)

  await page.goto('/s/shr-e2e')

  await expect(page.locator('.app-shell')).toHaveCount(0)
  await expect(page.locator('.public-share-header').getByRole('heading', { name: weddingMemory.displayName })).toBeVisible()
  await expect(page.locator('.public-share-meta')).toContainText('Viewer')
  await expect(page.locator('.public-share-meta')).toContainText('Ansehen')
  await expect(page.getByRole('tab', { name: 'Galerie' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Kapitel' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bearbeiten' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Memory teilen' })).toHaveCount(0)
})

test('V1 public viewer share shows map tracks and playlist privacy gates', async ({ page }) => {
  let osmRequests = 0
  let youtubeRequests = 0
  await page.route('https://*.tile.openstreetmap.org/**', async (route) => {
    osmRequests += 1
    await route.abort()
  })
  await page.route('https://www.youtube-nocookie.com/**', async (route) => {
    youtubeRequests += 1
    await route.abort()
  })
  await mockPublicViewerShareApi(page, {
    media: [geotaggedMedia],
    tracks: [publicTrack],
    playlists: [publicPlaylist],
    journalMarkdown: `[Playlist](veyq:playlist/${publicPlaylist.id})\n\n[Track](veyq:track/${publicTrack.id})`
  })

  await page.goto('/s/shr-e2e')

  await page.getByRole('tab', { name: 'Karte' }).click()
  await expect(page.getByText('Externe Kartenkacheln sind deaktiviert')).toBeVisible()
  await expect(page.locator('.leaflet-marker-icon')).toHaveCount(0)
  await expect.poll(() => osmRequests).toBe(0)

  await page.getByRole('tab', { name: 'Tracks' }).click()
  await expect(page.getByRole('heading', { name: 'Morning Walk' })).toBeVisible()
  await expect(page.locator('dd').getByText('2,4 km')).toBeVisible()

  await page.getByRole('tab', { name: 'Playlists' }).click()
  await expect(page.getByText('Roadtrip Soundtrack')).toBeVisible()
  await expect(page.getByText('YouTube ist deaktiviert')).toBeVisible()
  await expect(page.locator('.memory-playlist-embed iframe')).toHaveCount(0)
  await expect.poll(() => youtubeRequests).toBe(0)

  await page.getByRole('button', { name: 'YouTube laden' }).click()
  await expect(page.locator('.memory-playlist-embed iframe')).toHaveAttribute(
    'src',
    'https://www.youtube-nocookie.com/embed/videoseries?list=PL1234567890abcdef'
  )
})

test('V1 uploader-only public share uploads without exposing the gallery', async ({ page }) => {
  let uploadSessionRequest: unknown = null
  const uploadedChunks: string[] = []
  await mockPublicUploaderShareApi(page, {
    onCreateUpload: (body) => {
      uploadSessionRequest = body
    },
    onChunkUpload: (chunkIndex) => {
      uploadedChunks.push(chunkIndex)
    }
  })

  await page.goto('/s/shr-upload')

  await expect(page.getByRole('heading', { name: 'Sommerreise Upload' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Zur Erinnerung beitragen' })).toBeVisible()
  await expect(page.getByText('Dieser Link zeigt keine Galerie oder andere Beiträge.')).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Galerie' })).toHaveCount(0)

  await page.locator('.public-share-upload input.memory-upload-native-fallback').setInputFiles({
    name: 'beach.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from('beach-data')
  })
  await expect(page.getByText('1 Medien ausgewählt')).toBeVisible()

  await page.locator('.public-share-upload').getByRole('button', { name: 'Hochladen' }).click()

  await expect.poll(() => uploadSessionRequest).toMatchObject({
    filename: 'beach.jpg',
    mimeType: 'image/jpeg',
    sizeBytes: 10,
    title: 'beach.jpg'
  })
  await expect.poll(() => uploadedChunks).toEqual(['0'])
  await expect(page.getByText('1 Medien wurden hochgeladen.')).toBeVisible()
})
