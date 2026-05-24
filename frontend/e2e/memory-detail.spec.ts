import { expect, test } from '@playwright/test'
import { mockMemoryDetailApi, mockPublicViewerShareApi, weddingMemory } from './support/api'

test('memory detail renders breadcrumb context and memory tabs', async ({ page }) => {
  await mockMemoryDetailApi(page)

  await page.goto(`/friends${weddingMemory.path}`)

  await expect(page.getByRole('heading', { name: weddingMemory.displayName })).toBeVisible()
  await expect(page.locator('.app-breadcrumb')).toContainText('Friends')
  await expect(page.locator('.app-breadcrumb')).toContainText('reisen')
  await expect(page.locator('.app-breadcrumb')).toContainText(weddingMemory.displayName)

  await expect(page.getByRole('tab', { name: 'Galerie' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Unter-Memories' })).toBeVisible()
  await expect(page.getByText('1 Unter-Memories')).toBeVisible()

  await page.getByRole('tab', { name: 'Unter-Memories' }).click()
  await expect(page.getByRole('button', { name: /Abendessen/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Unter-Memory erstellen' })).toBeVisible()
})

test('memory topbar exposes upload as icon action without text label', async ({ page }) => {
  await mockMemoryDetailApi(page)

  await page.goto(`/friends${weddingMemory.path}`)

  const topbar = page.locator('.workspace-topbar')
  await expect(topbar.getByRole('button', { name: 'Hochladen' })).toBeVisible()
  await expect(topbar).not.toContainText('Hochladen')
})

test('share dialog manages password protection with a toggle', async ({ page }) => {
  let createdShare: unknown = null
  let updatedShare: unknown = null
  await mockMemoryDetailApi(page, {
    shares: [
      {
        id: 'shr-e2e',
        memoryId: weddingMemory.id,
        name: 'Familie',
        role: 'VIEWER',
        enabled: true,
        shareUrl: '/s/shr-e2e',
        hasPassword: true,
        createdAt: '2026-05-24T10:00:00Z',
        updatedAt: '2026-05-24T10:00:00Z'
      }
    ],
    onCreateShare: (body) => {
      createdShare = body
    },
    onUpdateShare: (body) => {
      updatedShare = body
    }
  })

  await page.goto(`/friends${weddingMemory.path}`)
  await page.getByRole('button', { name: 'Teilen' }).click()

  const shareDialog = page.getByRole('dialog', { name: 'Memory teilen' })
  await expect(shareDialog).toBeVisible()
  await expect(shareDialog.getByText('http://127.0.0.1:8081/s/shr-e2e')).toBeVisible()

  await shareDialog.getByRole('button', { name: 'Share erstellen' }).click()
  await expect.poll(() => createdShare).toMatchObject({
    role: 'VIEWER',
    password: null
  })

  await shareDialog.getByLabel('Passwortschutz aktivieren').check()
  await expect(shareDialog.getByRole('button', { name: 'Share erstellen' })).toBeDisabled()
  await shareDialog.getByRole('textbox', { name: 'Passwort' }).fill('share-password123')
  await shareDialog.getByRole('button', { name: 'Share erstellen' }).click()
  await expect.poll(() => createdShare).toMatchObject({
    role: 'VIEWER',
    password: 'share-password123'
  })

  await shareDialog.getByRole('button', { name: 'Passwort ändern' }).click()
  const passwordDialog = page.getByRole('dialog', { name: 'Passwortschutz' })
  await expect(passwordDialog).toBeVisible()
  await passwordDialog.getByLabel('Passwortschutz aktivieren').uncheck()
  await passwordDialog.getByRole('button', { name: 'Speichern' }).click()
  await expect.poll(() => updatedShare).toMatchObject({
    password: ''
  })
})

test('public share route renders the shared memory view under /s', async ({ page }) => {
  await mockPublicViewerShareApi(page)

  await page.goto('/s/shr-e2e')

  await expect(page).toHaveURL(/\/s\/shr-e2e$/)
  await expect(page.getByRole('heading', { name: weddingMemory.displayName })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Galerie' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Unter-Memories' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Info' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bearbeiten' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Teilen' })).toHaveCount(0)
})
