import { expect, test } from '@playwright/test'
import {
  adminUser,
  mockAuthenticatedOwnerWithoutTenants,
  mockInitialSetupApi
} from './support/api'

test('initial setup keeps tenant creation outside the workspace shell', async ({ page }) => {
  await mockInitialSetupApi(page)

  await page.goto('/setup')

  await page.getByLabel('E-Mail').fill(adminUser.email)
  await page.getByLabel('Passwort').fill('correct horse battery staple')
  await page.getByLabel('Anzeigename').fill(adminUser.displayName)
  await page.getByRole('button', { name: 'Admin erstellen' }).click()

  await expect(page.locator('.app-shell')).toHaveCount(0)
  await expect(page.locator('.public-shell')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Tenant erstellen' })).toBeVisible()
  await expect(page.getByLabel('Domain')).toHaveValue(new URL(page.url()).host)

  await page.getByRole('button', { name: 'Tenant erstellen' }).click()

  await expect(page).toHaveURL(/\/friends$/)
  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.getByText('Friends').first()).toBeVisible()
})

test('server owners can create a tenant from an empty workspace shell', async ({ page }) => {
  await mockAuthenticatedOwnerWithoutTenants(page)

  await page.goto('/')

  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.getByText("Currently, you don't have any tenants")).toBeVisible()

  await page.getByRole('button', { name: 'Tenant erstellen' }).click()

  await expect(page.getByRole('dialog', { name: 'Tenant erstellen' })).toBeVisible()
  await expect(page.getByLabel('Domain')).toHaveValue(new URL(page.url()).host)
})
