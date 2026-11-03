// Generates PWA icon PNGs (192/512/maskable/apple/favicon) without external deps.
// Pure Node: builds an RGBA canvas, encodes PNG chunks with zlib + CRC32.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(__dirname, '..', 'public', 'icons');

const COLORS = {
  bg: [11, 15, 23, 255],           // #0b0f17
  bgRing: [56, 189, 248, 255],      // #38bdf8 accent
  barLow: [56, 189, 248, 255],      // #38bdf8
  barHigh: [125, 211, 252, 255],    // #7dd3fc
};

// ---------- tiny PNG encoder ----------
const crcTable = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([len, typeBuf, data, crc]);
}
function encodePng(width, height, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // color type RGBA
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0; // filter: none
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const clamp255 = (v) => Math.max(0, Math.min(255, Math.round(v)));
function lerp(a, b, t) { return a + (b - a) * t; }

function drawBars(rgba, W, H, opts) {
  const { cx, baselineY, barW, gap, minH, maxH, barCount } = opts;
  const totalW = barCount * barW + (barCount - 1) * gap;
  const x0 = cx - totalW / 2;
  for (let i = 0; i < barCount; i++) {
    const frac = (i + 1) / barCount;
    const h = lerp(minH, maxH, frac);
    const bx0 = Math.floor(x0 + i * (barW + gap));
    const bx1 = Math.floor(bx0 + barW);
    const by0 = Math.floor(baselineY - h);
    const by1 = Math.ceil(baselineY);
    const color = i === barCount - 1 ? COLORS.barHigh : COLORS.barLow;
    for (let y = Math.max(0, by0); y < Math.min(H, by1); y++) {
      for (let x = Math.max(0, bx0); x < Math.min(W, bx1); x++) {
        const idx = (y * W + x) * 4;
        rgba[idx] = color[0];
        rgba[idx + 1] = color[1];
        rgba[idx + 2] = color[2];
        rgba[idx + 3] = 255;
      }
    }
  }
  const lineY = Math.round(baselineY + (W * 0.05));
  const lineX0 = Math.floor(x0 - barW * 0.35);
  const lineX1 = Math.ceil(x0 + totalW + barW * 0.35);
  if (lineY < H) {
    for (let y = lineY; y < Math.min(H, lineY + Math.max(2, Math.round(W * 0.018))); y++) {
      for (let x = Math.max(0, lineX0); x < Math.min(W, lineX1); x++) {
        const idx = (y * W + x) * 4;
        rgba[idx] = COLORS.bgRing[0];
        rgba[idx + 1] = COLORS.bgRing[1];
        rgba[idx + 2] = COLORS.bgRing[2];
        rgba[idx + 3] = 255;
      }
    }
  }
}

function inRoundedRect(px, py, W, H, r) {
  if (px < 0 || py < 0 || px >= W || py >= H) return false;
  const cx = W / 2 - 0.5, cy = H / 2 - 0.5;
  const dx = Math.abs(px - cx), dy = Math.abs(py - cy);
  if (dx <= (W / 2 - r) || dy <= (H / 2 - r)) return true;
  const ax = dx - (W / 2 - r), ay = dy - (H / 2 - r);
  return (ax * ax + ay * ay) <= r * r;
}

function makeIcon(size, { maskable = false, rounded = true } = {}) {
  const W = size, H = size;
  const rgba = Buffer.alloc(W * H * 4);
  rgba.fill(0);
  const bg = COLORS.bg;
  const radius = rounded && !maskable ? Math.round(W * 0.22) : 0;
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const inside = maskable ? true : inRoundedRect(x, y, W, H, radius);
      if (inside) {
        const idx = (y * W + x) * 4;
        const t = y / (H - 1);
        const shade = maskable ? 1 : 1 - t * 0.12;
        rgba[idx] = clamp255(bg[0] * shade);
        rgba[idx + 1] = clamp255(bg[1] * shade);
        rgba[idx + 2] = clamp255(bg[2] * shade + t * 6);
        rgba[idx + 3] = 255;
      }
    }
  }
  const safeFrac = maskable ? 0.60 : 0.5;
  const minD = W * (1 - safeFrac);
  const barW = maskable ? Math.round(W * 0.11) : Math.round(W * 0.105);
  const gap = maskable ? Math.round(W * 0.045) : Math.round(W * 0.05);
  const barCount = 4;
  const cx = W / 2;
  const baselineY = H / 2 + minD * 0.32;
  const minH = maskable ? W * 0.12 : W * 0.11;
  const maxH = maskable ? W * 0.34 : W * 0.30;
  drawBars(rgba, W, H, { cx, baselineY, barW, gap, minH, maxH, barCount });
  return encodePng(W, H, rgba);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
function write(name, size, opts) {
  const p = path.join(OUT_DIR, name);
  fs.writeFileSync(p, makeIcon(size, opts));
  console.log(' - ' + name + ' (' + size + 'x' + size + ')');
}

console.log('Wrote to ' + OUT_DIR);
write('icon-192.png', 192, { maskable: false, rounded: true });
write('icon-512.png', 512, { maskable: false, rounded: true });
write('maskable-192.png', 192, { maskable: true });
write('maskable-512.png', 512, { maskable: true });
write('apple-touch-icon.png', 180, { maskable: false, rounded: true });
write('favicon-32.png', 32, { maskable: false, rounded: true });
write('favicon-64.png', 64, { maskable: false, rounded: true });
console.log('Done');
