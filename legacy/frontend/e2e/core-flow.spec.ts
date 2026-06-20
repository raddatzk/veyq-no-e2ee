import { expect, test } from '@playwright/test'

// End-to-end against a REAL (in-memory) backend: the headline E2EE journey, all crypto client-side.
//   cold setup (Emergency Kit) → login → create a Memory → upload an (encrypted) photo → see it decrypted →
//   write the journal → see it rendered.
// Single serial test: the backend is a shared instance set up exactly once per run (see playwright.config.ts).

// A 1×1 PNG — enough for the client to downscale into thumb/preview and round-trip through the sealed blob store.
const PNG_1x1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
)

const EMAIL = 'e2e-owner@example.test'
const PASSWORD = 'e2e-correct-horse-staple'

test('cold setup → login → memory → encrypted upload → journal', async ({ page }) => {
  test.setTimeout(120_000)

  await test.step('cold setup creates the server owner and shows the Emergency Kit', async () => {
    await page.goto('/') // boots → setupRequired → redirects to /setup
    await page.locator('input[type="text"]').first().fill('E2E Owner')
    await page.locator('input[type="email"]').first().fill(EMAIL)
    await page.locator('#setup-password').fill(PASSWORD)
    await page.getByRole('button', { name: /Server einrichten/i }).click()
    await expect(page.locator('.kit-secret')).toBeVisible({ timeout: 30_000 })
  })

  await test.step('proceeding lands on the prefilled login; password unlocks the session', async () => {
    await page.getByRole('button', { name: /weiter zur Anmeldung/i }).click()
    await expect(page.locator('#login-password')).toBeVisible()
    await page.locator('#login-password').fill(PASSWORD)
    await page.getByRole('button', { name: /^Anmelden$/ }).click()
    // The space-based workspace shell renders once the master key is unlocked.
    await expect(page.locator('.tenant-sidebar')).toBeVisible({ timeout: 30_000 })
  })

  await test.step('create a memory (sealed node) and navigate into it', async () => {
    await page.getByRole('button', { name: 'Memory erstellen' }).click()
    await page.locator('.p-dialog input').first().fill('E2E Reise')
    await page.locator('.p-dialog').getByRole('button', { name: 'Erstellen' }).click()
    await expect(page.locator('.memory-tabs')).toBeVisible({ timeout: 30_000 })
  })

  await test.step('upload an encrypted photo and see it decrypted in the gallery', async () => {
    await page.locator('.upload-input').setInputFiles({ name: 'e2e.png', mimeType: 'image/png', buffer: PNG_1x1 })
    const tile = page.locator('.gallery-tile img').first()
    await expect(tile).toBeVisible({ timeout: 30_000 })
    await expect(tile).toHaveAttribute('src', /^blob:/) // decrypted client-side to an object URL
  })

  await test.step('write the journal and see the rendered Markdown', async () => {
    await page.locator('.memory-tabs button', { hasText: 'Journal' }).click()
    await page.getByRole('button', { name: 'Bearbeiten', exact: true }).click()
    await page.locator('.journal-editor').fill('# E2E\nVerschlüsselt gespeichert.')
    await page.getByRole('button', { name: /^Speichern$/ }).click()
    await expect(page.locator('.journal-body')).toContainText('Verschlüsselt gespeichert.', { timeout: 30_000 })
  })
})
