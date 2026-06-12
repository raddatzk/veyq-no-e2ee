import { expect, test } from '@playwright/test'
import {
  adminUser,
  mockAuthenticatedOwnerWithoutTenants,
  mockInitialSetupApi
} from './support/api'

test('initial setup keeps tenant creation outside the workspace shell', async ({ page }) => {
  await mockInitialSetupApi(page)

  await page.goto('/setup')

  await page.getByRole('tab', { name: 'Passwort' }).click()
  await page.getByLabel('E-Mail').fill(adminUser.email)
  await page.getByLabel('Passwort').fill('correct horse battery staple')
  await page.getByLabel('Anzeigename').fill(adminUser.displayName)
  await page.getByRole('button', { name: 'Admin erstellen' }).click()

  await expect(page.locator('.app-shell')).toHaveCount(0)
  await expect(page.locator('.public-shell')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bereich erstellen' })).toBeVisible()
  await expect(page.getByLabel('Permalink')).toHaveCount(0)
  await expect(page.getByLabel('Domain')).toHaveValue(new URL(page.url()).host)
  await page.getByLabel('Owner-E-Mail').fill('tenant-owner@example.test')

  await page.getByRole('button', { name: 'Bereich erstellen' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.getByText('Du hast aktuell noch keinen Bereich für deine Memories.')).toBeVisible()
})

test('server owners can create a tenant from an empty workspace shell', async ({ page }) => {
  await mockAuthenticatedOwnerWithoutTenants(page)

  await page.goto('/')

  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.getByText('Du hast aktuell noch keinen Bereich für deine Memories.')).toBeVisible()

  await page.getByRole('button', { name: 'Bereich erstellen' }).click()

  await expect(page.getByRole('dialog', { name: 'Bereich erstellen' })).toBeVisible()
  await expect(page.getByLabel('Domain')).toHaveValue(new URL(page.url()).host)
  await expect(page.getByLabel('Owner-E-Mail')).toBeVisible()
})
