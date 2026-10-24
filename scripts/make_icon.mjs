// 零依赖图标生成器：「三股编绳」标记，超采样抗锯齿，手写 PNG 编码
//
// 设计：三条相位差 120° 的正弦束横向交织 —— 订阅、tailnet、本机三股网络
// 编成一根更结实的绳子（对应应用名 Braid）。背景沿用深海蓝→青绿 45° 渐变。
// 两种布局：
//   bleed     传统方形/圆形启动图标，编绳贯穿整个画布
//   contained 自适应图标前景，整条编绳收缩进安全圆（半径 ~61%）
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

const RES = new URL('../app/src/main/res', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'); // 仓库内相对定位
const M = 1024; // 设计基准尺寸

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePNG(w, h, rgba) {
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const C1 = [0x0a, 0x25, 0x40]; // 深海军蓝
const C2 = [0x12, 0xa5, 0x94]; // 青绿

// 编绳几何（设计坐标，原点居中）。widths 三条束的线宽，halo 是束底下的暗衬宽度；
// fade > 0 时束在两端线性渐隐（自适应前景用，避免圆头在安全圆边缘显得毛糙）
const GEOM_BLEED = { x0: -560, x1: 560, amp: 170, period: 660, widths: [52, 62, 52], halo: 78, fade: 0 };
const GEOM_CONTAINED = { x0: -290, x1: 290, amp: 112, period: 430, widths: [36, 44, 36], halo: 58, fade: 70 };
// 相位 0 的那股正好穿过中心，画成最亮的主体
const PHASES = [0, (2 * Math.PI) / 3, (4 * Math.PI) / 3];
const ALPHAS = [1.0, 0.78, 0.78];

// 点到正弦束的近似垂直距离；束端点之外算到端点的直线距离
function strandDist(mx, my, g, phase) {
  const cx = Math.min(g.x1, Math.max(g.x0, mx));
  const w = (2 * Math.PI * cx) / g.period + phase;
  const y = g.amp * Math.sin(w);
  if (mx >= g.x0 && mx <= g.x1) {
    const dydx = ((2 * Math.PI) / g.period) * g.amp * Math.cos(w);
    return Math.abs(my - y) / Math.sqrt(1 + dydx * dydx);
  }
  return Math.hypot(mx - cx, my - y);
}

function render(size, round, g) {
  const SS = 4;
  const scale = size / M;
  const rgba = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, cG = 0, b = 0, inside = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const fx = x + (sx + 0.5) / SS;
          const fy = y + (sy + 0.5) / SS;
          if (round) {
            if (Math.hypot(fx - size / 2, fy - size / 2) > size / 2) continue;
          }
          inside++;

          // 背景：45° 线性渐变 + 中心柔光
          const t = (fx + fy) / (2 * size);
          let cr = C1[0] + (C2[0] - C1[0]) * t;
          let cg = C1[1] + (C2[1] - C1[1]) * t;
          let cb = C1[2] + (C2[2] - C1[2]) * t;
          const mixWhite = (a) => {
            cr += (255 - cr) * a; cg += (255 - cg) * a; cb += (255 - cb) * a;
          };
          const mixBlack = (a) => {
            cr *= 1 - a; cg *= 1 - a; cb *= 1 - a;
          };
          const glow = Math.max(0, 1 - Math.hypot(fx - size / 2, fy - size / 2) / (size * 0.35)) * (60 / 255);
          if (glow > 0) mixWhite(glow);

          // 编绳：暗衬 → 依次叠三股；fade 让束在两端渐隐
          const mx = (fx - size / 2) / scale;
          const my = (fy - size / 2) / scale;
          const d0 = strandDist(mx, my, g, PHASES[0]);
          const d1 = strandDist(mx, my, g, PHASES[1]);
          const d2 = strandDist(mx, my, g, PHASES[2]);
          const fade = g.fade > 0
            ? Math.max(0, Math.min(1, Math.min(mx - g.x0, g.x1 - mx) / g.fade))
            : 1;
          if (Math.min(d0, d1, d2) < g.halo) mixBlack(0.22 * fade);
          if (d0 < g.widths[0] / 2) mixWhite(ALPHAS[0] * fade);
          if (d1 < g.widths[1] / 2) mixWhite(ALPHAS[1] * fade);
          if (d2 < g.widths[2] / 2) mixWhite(ALPHAS[2] * fade);

          r += cr; cG += cg; b += cb;
        }
      }
      const o = (y * size + x) * 4;
      if (inside === 0) {
        rgba[o] = rgba[o + 1] = rgba[o + 2] = rgba[o + 3] = 0;
      } else {
        rgba[o] = Math.round(r / inside);
        rgba[o + 1] = Math.round(cG / inside);
        rgba[o + 2] = Math.round(b / inside);
        rgba[o + 3] = Math.round((inside / (SS * SS)) * 255);
      }
    }
  }
  return encodePNG(size, size, rgba);
}

const legacy = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
const foreground = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };

const out = [];
for (const [k, px] of Object.entries(legacy)) {
  const dir = path.join(RES, `mipmap-${k}`);
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), render(px, false, GEOM_BLEED));
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), render(px, true, GEOM_BLEED));
  fs.writeFileSync(path.join(dir, 'ic_launcher_foreground.png'), render(foreground[k], false, GEOM_CONTAINED));
  out.push(`${k}: ${px}px`);
}
const PREVIEW = new URL('../preview/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
fs.writeFileSync(path.join(PREVIEW, 'icon_preview.png'), render(512, false, GEOM_BLEED));
fs.writeFileSync(path.join(PREVIEW, 'icon_preview_round.png'), render(512, true, GEOM_BLEED));
fs.writeFileSync(path.join(PREVIEW, 'icon_preview_fg.png'), render(432, false, GEOM_CONTAINED));
console.log('done', out.join(', '));
