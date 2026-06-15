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

// download: open the modal, pick "Nur diese Memory", capture the zip, verify its contents
await page.locator('.detail-download').click()
await page.waitForSelector('.dl-option')
await page.waitForTimeout(600) // counts load
await page.screenshot({ path: '/tmp/veyq-web-download.png' })
const [download] = await Promise.all([
  page.waitForEvent('download'),
  page.locator('.dl-option').first().click(),
])
const zipPath = '/tmp/veyq-memory.zip'
await download.saveAs(zipPath)
const zip = new Uint8Array(readFileSync(zipPath))
const entries = parseZip(zip)
const allPng = entries.every((e) => e.data[0] === 137 && e.data[1] === 80 && e.data[2] === 78 && e.data[3] === 71)
console.log('download ok, zip entries:', entries.length, 'all valid PNG:', allPng, 'names:', entries.map((e) => e.name).join(' '))

await browser.close()
console.log('done')

function parseZip(zip) {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
  if (dv.getUint32(zip.length - 22, true) !== 0x06054b50) throw new Error('bad EOCD')
  const count = dv.getUint16(zip.length - 22 + 10, true)
  const out = []
  let o = 0
  for (let i = 0; i < count; i++) {
    if (dv.getUint32(o, true) !== 0x04034b50) throw new Error('bad local header')
    const size = dv.getUint32(o + 18, true)
    const nameLen = dv.getUint16(o + 26, true)
    const extraLen = dv.getUint16(o + 28, true)
    const name = new TextDecoder().decode(zip.subarray(o + 30, o + 30 + nameLen))
    const start = o + 30 + nameLen + extraLen
    out.push({ name, data: zip.subarray(start, start + size) })
    o = start + size
  }
  return out
}
