import { chromium } from 'playwright'

const widths = [320, 360, 375, 390, 430, 768, 1024, 1280, 1440, 1920, 2560, 3440]
const heights = [600, 900, 1200]
const routes = ['/', '/explore', '/login', '/rooms', '/admin/login']
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ||
  (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : undefined)
const browser = await chromium.launch({ headless: true, executablePath })
const page = await browser.newPage()
const results = []

try {
  for (const route of routes) {
    const response = await page.goto(`http://127.0.0.1:3000${route}`, { waitUntil: 'domcontentloaded' })
    for (const width of widths) {
      for (const height of heights) {
        await page.setViewportSize({ width, height })
        const layout = await page.evaluate(() => ({
          viewportWidth: document.documentElement.clientWidth,
          documentWidth: document.documentElement.scrollWidth,
          bodyWidth: document.body.scrollWidth,
          visibleControlsOutsideViewport: [...document.querySelectorAll('input,button,select,a')]
            .filter(element => element.getClientRects().length > 0)
            .filter(element => {
              const rect = element.getBoundingClientRect()
              return rect.left < -1 || rect.right > document.documentElement.clientWidth + 1
            }).length
        }))
        results.push({ route, width, height, status: response?.status(), ...layout })
      }
    }
  }
} finally {
  await browser.close()
}

const failures = results.filter(result => result.status !== 200 ||
  result.documentWidth > result.width || result.bodyWidth > result.width ||
  result.visibleControlsOutsideViewport > 0)
console.log(JSON.stringify({ checked: results.length, routes, widths, heights, failures }, null, 2))
if (failures.length) process.exitCode = 1
