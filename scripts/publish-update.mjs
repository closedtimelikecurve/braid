// 把编好的 APK 发布成自建更新源：写 version.json + 拷贝 APK
//
// 用法：
//   node scripts/publish-update.mjs <apk路径> [更新说明]
// 环境变量：
//   UPDATE_DIR    清单与 APK 的输出目录（默认 ./update-site）
//   UPDATE_HOST   对外访问地址 host:port（写进清单的 downloadUrl，默认 127.0.0.1:8088）
//
// 服务端只需要任意静态文件服务，例如：
//   python -m http.server 8088 --directory <UPDATE_DIR>
// 手机端在「应用设置 → 更新设置 → 自建更新源」里填
//   http://<你的机器>:8088/braid/version.json
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const AAPT2 = process.env.AAPT2 ?? 'aapt2';
const PUBLISH_DIR = process.env.UPDATE_DIR ?? path.join(process.cwd(), 'update-site');
const HOST = process.env.UPDATE_HOST ?? '127.0.0.1:8088';

const apkPath = process.argv[2];
const notes = process.argv[3] ?? '自建更新源发布';

if (!apkPath || !fs.existsSync(apkPath)) {
  console.error('用法: node scripts/publish-update.mjs <apk路径> [更新说明]');
  process.exit(1);
}

// 从 APK 里读真实版本号，避免清单和包不一致导致「一直提示更新」
const badging = execFileSync(AAPT2, ['dump', 'badging', apkPath], { encoding: 'utf8' });
const versionCode = Number(/(?:^|\s)versionCode='(\d+)'/.exec(badging)?.[1] ?? 0);
const versionName = /versionName='([^']*)'/.exec(badging)?.[1] ?? 'unknown';
if (!versionCode) {
  console.error('解析 versionCode 失败（需要 aapt2，可用 AAPT2 环境变量指定路径）');
  process.exit(1);
}

// 版本号必须比线上那份大，否则客户端永远等不到「有新版本」
const manifestPath = path.join(PUBLISH_DIR, 'version.json');
if (fs.existsSync(manifestPath)) {
  const previous = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  if (versionCode <= previous.versionCode) {
    console.error(`版本号没有前进：新=${versionCode} 线上=${previous.versionCode}`);
    process.exit(1);
  }
}

fs.mkdirSync(PUBLISH_DIR, { recursive: true });
const target = path.join(PUBLISH_DIR, 'braid-latest.apk');
fs.copyFileSync(apkPath, target);

const sha256 = createHash('sha256').update(fs.readFileSync(target)).digest('hex');
const manifest = {
  versionCode,
  versionName,
  downloadUrl: `http://${HOST}/braid-latest.apk`,
  releaseUrl: `http://${HOST}/`,
  releaseNotes: notes,
  isPrerelease: versionName.includes('-'),
  fileSize: fs.statSync(target).size,
};
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));

console.log(`已发布 ${versionName} (${versionCode})`);
console.log(`  APK  ${(manifest.fileSize / 1048576).toFixed(1)} MB`);
console.log(`  sha256 ${sha256.slice(0, 16)}…`);
console.log(`  清单 http://${HOST}/braid/version.json`);
