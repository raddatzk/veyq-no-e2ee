import { expect, test, type Page } from '@playwright/test'
import { mockMemoryDetailApi, mockPublicViewerShareApi, weddingMemory } from './support/api'

test('memory detail renders breadcrumb context and memory tabs', async ({ page }) => {
  await mockMemoryDetailApi(page)

  await page.goto(weddingMemory.path)

  await expect(page.getByRole('heading', { name: weddingMemory.displayName })).toBeVisible()
  await expect(page.locator('.app-breadcrumb')).toContainText('Friends')
  await expect(page.locator('.app-breadcrumb')).toContainText('reisen')
  await expect(page.locator('.app-breadcrumb')).toContainText(weddingMemory.displayName)

  await expect(page.getByRole('tab', { name: 'Galerie' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Kapitel' })).toBeVisible()
  await expect(page.getByText('1 Kapitel')).toBeVisible()

  let focusedTabName: string | null = null
  for (let index = 0; index < 20; index += 1) {
    await page.keyboard.press('Tab')
    focusedTabName = await page.evaluate(() => {
      const activeElement = document.activeElement
      return activeElement?.getAttribute('role') === 'tab' ? activeElement.textContent?.trim() ?? null : null
    })
    if (focusedTabName) break
  }
  expect(focusedTabName).toBeTruthy()
  const focusedTab = page.getByRole('tab', { name: focusedTabName ?? '' })
  await expect(focusedTab).toHaveCSS('outline-style', 'none')
  await expect.poll(() => focusedTab.evaluate((element) => getComputedStyle(element).boxShadow)).not.toBe('none')

  await page.getByRole('tab', { name: 'Kapitel' }).click()
  await expect(page.getByRole('button', { name: /Abendessen/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Kapitel erstellen' })).toBeVisible()
})

test('memory detail exposes upload as a primary memory action', async ({ page }) => {
  await mockMemoryDetailApi(page)

  await page.goto(weddingMemory.path)

  await expect(page.locator('.memory-detail-actions').getByRole('button', { name: 'Hochladen' })).toBeVisible()
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

  await page.goto(weddingMemory.path)
  await openShareDialog(page)

  const shareDialog = page.getByRole('dialog', { name: 'Memory teilen' })
  await expect(shareDialog).toBeVisible()
  await expect(shareDialog.getByText('http://127.0.0.1:8081/s/shr-e2e')).toBeVisible()

  await shareDialog.getByRole('button', { name: 'Neue Freigabe anlegen' }).click()
  const createDialog = page.getByRole('dialog', { name: 'Neue Freigabe anlegen' })
  await expect(createDialog).toBeVisible()
  await createDialog.getByRole('button', { name: 'Link erstellen' }).click()
  await expect.poll(() => createdShare).toMatchObject({
    role: 'VIEWER',
    password: null
  })

  await shareDialog.getByRole('button', { name: 'Neue Freigabe anlegen' }).click()
  await createDialog.getByLabel('Passwortschutz aktivieren').check()
  await expect(createDialog.getByRole('button', { name: 'Link erstellen' })).toBeDisabled()
  await createDialog.getByRole('textbox', { name: 'Passwort' }).fill('share-password123')
  await createDialog.getByRole('button', { name: 'Link erstellen' }).click()
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
  await expect(page.getByRole('tab', { name: 'Kapitel' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Info' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bearbeiten' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Memory teilen' })).toHaveCount(0)
})

async function openShareDialog(page: Page) {
  const directShareButton = page.locator('.memory-detail-actions').getByRole('button', { name: 'Memory teilen' })
  if (await directShareButton.isVisible()) {
    await directShareButton.click()
    return
  }

  await page.locator('.memory-detail-actions').getByRole('button', { name: 'Mehr' }).click()
  await page.getByRole('menuitem', { name: 'Memory teilen' }).click()
}
