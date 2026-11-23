import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const PORT = Number(process.env.DEFAULT_APP_PORT || 3000);
const APK_PATH = path.resolve(process.cwd(), 'output', 'DeliFar.apk');
const FALLBACK_APK_PATH = path.resolve(process.cwd(), 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');

function resolveApk() {
  if (fs.existsSync(APK_PATH)) return APK_PATH;
  if (fs.existsSync(FALLBACK_APK_PATH)) return FALLBACK_APK_PATH;
  return null;
}

function getApkMeta(filePath) {
  const stat = fs.statSync(filePath);
  const hash = crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
  return {
    sizeBytes: stat.size,
    sizeMb: (stat.size / (1024 * 1024)).toFixed(2),
    sha256: hash,
    updatedAt: stat.mtime.toISOString(),
  };
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url || '/').split('?')[0];

  if (urlPath === '/DeliFar.apk' || urlPath === '/download' || urlPath === '/download/DeliFar.apk') {
    const apk = resolveApk();
    if (!apk) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('APK not found. Build the Android project first.');
      return;
    }
    const stat = fs.statSync(apk);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="DeliFar.apk"',
      'Content-Length': String(stat.size),
      'Cache-Control': 'no-cache',
    });
    fs.createReadStream(apk).pipe(res);
    return;
  }

  const apk = resolveApk();
  const meta = apk ? getApkMeta(apk) : null;

  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>DeliFar — Android APK Download</title>
  <style>
    :root {
      --bg: #f6f5f1;
      --surface: #ffffff;
      --primary: #183a37;
      --primary-hover: #122c2a;
      --accent: #d97706;
      --ink: #141b1a;
      --muted: #51605e;
      --border: #d4ddd9;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg);
      color: var(--ink);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
    }
    .card {
      width: 100%;
      max-width: 520px;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 18px;
      padding: 28px;
      box-shadow: 0 10px 30px rgba(20, 27, 26, 0.06);
    }
    .header {
      display: flex;
      align-items: center;
      gap: 14px;
      margin-bottom: 18px;
    }
    .icon {
      width: 52px;
      height: 52px;
      border-radius: 14px;
      background: var(--primary);
      display: grid;
      place-items: center;
      color: #f59e0b;
      font-weight: 800;
      font-size: 22px;
      flex-shrink: 0;
    }
    h1 { margin: 0; font-size: 24px; line-height: 1.2; }
    .sub { margin: 4px 0 0; color: var(--muted); font-size: 14px; }
    .btn {
      display: block;
      width: 100%;
      text-align: center;
      background: var(--primary);
      color: #fff;
      text-decoration: none;
      font-weight: 600;
      font-size: 16px;
      padding: 14px 18px;
      border-radius: 12px;
      margin: 20px 0;
      transition: background 0.15s ease;
    }
    .btn:hover { background: var(--primary-hover); }
    .meta {
      background: #f3f5f4;
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 14px;
      font-size: 13px;
      display: grid;
      gap: 8px;
    }
    .row { display: flex; justify-content: space-between; gap: 12px; }
    .k { color: var(--muted); }
    .v { font-weight: 600; text-align: right; word-break: break-all; }
    .sha { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: var(--muted); word-break: break-all; margin-top: 4px; }
    .steps {
      margin: 18px 0 0;
      padding-left: 18px;
      color: var(--muted);
      font-size: 13.5px;
      line-height: 1.6;
    }
  </style>
</head>
<body>
  <main class="card">
    <div class="header">
      <div class="icon">DF</div>
      <div>
        <h1>DeliFar Debug APK</h1>
        <p class="sub">Native Android delivery &amp; pickup organizer (com.delifar.app)</p>
      </div>
    </div>

    ${meta ? `
      <a class="btn" href="/DeliFar.apk" download="DeliFar.apk">Download DeliFar.apk (${meta.sizeMb} MB)</a>
      <div class="meta">
        <div class="row"><span class="k">Package</span><span class="v">com.delifar.app</span></div>
        <div class="row"><span class="k">Version</span><span class="v">1.0.0 (code 1)</span></div>
        <div class="row"><span class="k">Android Support</span><span class="v">Android 8.0+ (API 26–36)</span></div>
        <div class="row"><span class="k">File Size</span><span class="v">${meta.sizeMb} MB (${meta.sizeBytes.toLocaleString()} bytes)</span></div>
        <div>
          <div class="k">SHA-256</div>
          <div class="sha">${meta.sha256}</div>
        </div>
      </div>
      <ol class="steps">
        <li>Download <strong>DeliFar.apk</strong> onto an Android 8.0+ device.</li>
        <li>Open the APK file and allow installation from your browser or file manager if prompted.</li>
        <li>To install via USB ADB: <code>adb install -r DeliFar.apk</code></li>
      </ol>
    ` : `<p>APK not built yet.</p>`}
  </main>
</body>
</html>`;

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
  res.end(html);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`DeliFar APK download server running on http://0.0.0.0:${PORT}`);
});
