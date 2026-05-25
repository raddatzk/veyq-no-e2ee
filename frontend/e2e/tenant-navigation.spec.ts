import { expect, test } from '@playwright/test'
import {
  groupScopeRoot,
  mockAuthenticatedOwnerApi,
  personalScopeRoot,
  weddingMemory
} from './support/api'

test('tenant home renders memory navigation without legacy areas', async ({ page }) => {
  await mockAuthenticatedOwnerApi(page)

  await page.goto('/friends')

  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.locator('.sidebar-nav-link').filter({ hasText: 'Home' })).toBeVisible()
  await expect(page.locator('.sidebar-nav-link').filter({ hasText: 'Persönlich' })).toBeVisible()
  await expect(page.getByText('Favoriten')).toBeVisible()
  await expect(page.getByText('Gruppen')).toBeVisible()
  await expect(page.getByRole('link', { name: /Travel Gruppe/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Alben' })).toHaveCount(0)
})

test('sidebar scope links navigate to personal and group roots', async ({ page }) => {
  await mockAuthenticatedOwnerApi(page)

  await page.goto('/friends')
  await page.locator('.sidebar-nav-link').filter({ hasText: 'Persönlich' }).click()

  await expect(page).toHaveURL(new RegExp(`/friends${personalScopeRoot.path}$`))
  await expect(page.locator('.app-breadcrumb')).toContainText('Persönlich')

  await page.getByRole('link', { name: /Travel Gruppe/ }).click()

  await expect(page).toHaveURL(new RegExp(`/friends${groupScopeRoot.path}$`))
  await expect(page.locator('.app-breadcrumb')).toContainText('@Travel')
})

test('folder and memory creation live in content tiles, not the topbar', async ({ page }) => {
  let createdFolder: unknown = null
  let createdMemory: unknown = null
  await mockAuthenticatedOwnerApi(page, {
    onCreateFolder: (body) => {
      createdFolder = body
    },
    onCreateMemory: (body) => {
      createdMemory = body
    }
  })

  await page.goto('/friends')

  await expect(
    page.locator('.workspace-topbar').getByRole('button', { name: 'Ordner erstellen' })
  ).toHaveCount(0)
  await expect(
    page.locator('.workspace-topbar').getByRole('button', { name: 'Memory erstellen' })
  ).toHaveCount(0)
  await expect(page.locator('.workspace-topbar').getByRole('button', { name: 'Einstellungen' })).toBeVisible()
  await expect(page.locator('.workspace-topbar')).not.toContainText('Einstellungen')

  await page.getByRole('button', { name: 'Ordner erstellen' }).click()
  const folderDialog = page.getByRole('dialog', { name: 'Ordner erstellen' })
  await folderDialog.getByLabel('Name').fill('Reisen')
  await folderDialog.getByRole('button', { name: 'Erstellen' }).click()

  await expect.poll(() => createdFolder).toMatchObject({
    parentPath: '/',
    displayName: 'Reisen',
    name: 'reisen',
    scope: { type: 'TENANT' }
  })

  await page.getByRole('button', { name: 'Memory erstellen' }).click()
  const memoryDialog = page.getByRole('dialog', { name: 'Memory erstellen' })
  await memoryDialog.getByLabel('Name').fill('Sommerreise')
  await memoryDialog.getByLabel('Startdatum').fill('2026-05-03')
  await memoryDialog.getByLabel('Enddatum').fill('2026-05-05')
  await memoryDialog.getByRole('button', { name: 'Erstellen' }).click()

  await expect.poll(() => createdMemory).toMatchObject({
    displayName: 'Sommerreise',
    name: 'sommerreise',
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    folderPath: '/',
    scope: { type: 'TENANT' }
  })
})

test('memory cards render explicit cover images', async ({ page }) => {
  await mockAuthenticatedOwnerApi(page, {
    folderMemories: [weddingMemory]
  })

  await page.goto('/friends')

  await expect(page.getByRole('img', { name: 'Hochzeit Lisa & Kevin' })).toBeVisible()
  await expect(page.locator('.memory-card-icon')).toHaveCount(0)
})
