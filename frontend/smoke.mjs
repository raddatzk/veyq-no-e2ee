import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'

const { email, password, secretKey } = JSON.parse(readFileSync('/tmp/veyq-seed.json', 'utf8'))
const base = 'http://127.0.0.1:8081'

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1280, height: 820 }, deviceScaleFactor: 2 })
page.on('pageerror', (e) => console.log('PAGE ERROR:', e.message))

await page.goto(base, { waitUntil: 'networkidle' })
await page.waitForSelector('text=Anmelden', { timeout: 15000 })

const inputs = page.locator('.veyq-form input')
await inputs.nth(0).fill(email)
await inputs.nth(1).fill(password)
await inputs.nth(2).fill(secretKey)
await page.click('button:has-text("Anmelden")')

await page.waitForSelector('.home-brand', { timeout: 25000 })
await page.waitForTimeout(2000) // let covers decrypt + load
await page.screenshot({ path: '/tmp/veyq-web-home.png' })
console.log('home ok, memories:', await page.locator('.memory-card').count(), 'folders:', await page.locator('.folder-card').count())

await page.locator('.memory-card').first().click()
await page.waitForSelector('.gallery-tile', { timeout: 25000 })
await page.waitForTimeout(1500)
await page.screenshot({ path: '/tmp/veyq-web-gallery.png' })
console.log('gallery ok, tiles:', await page.locator('.gallery-tile').count())

await browser.close()
console.log('done')
