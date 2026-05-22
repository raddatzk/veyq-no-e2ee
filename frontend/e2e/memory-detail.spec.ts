import { expect, test } from '@playwright/test'
import { mockMemoryDetailApi, weddingMemory } from './support/api'

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
