// Renders an SVG to a PNG of the given size with the pre-installed Chromium (Playwright).
// Usage: node tools/store/render_svg.js <in.svg> <out.png> <width> <height>
const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const [input, output, width, height] = process.argv.slice(2);
  const w = parseInt(width, 10);
  const h = parseInt(height, 10);
  const svg = fs.readFileSync(input, 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  await page.setContent(`<html><body style="margin:0">${svg.replace('width="512" height="512"', `width="${w}" height="${h}"`)}</body></html>`);
  await page.screenshot({ path: output, clip: { x: 0, y: 0, width: w, height: h }, omitBackground: false });
  await browser.close();
})();
