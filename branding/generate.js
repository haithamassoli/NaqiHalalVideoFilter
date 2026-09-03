// Regenerates every Naqi logo asset — SVG masters, Android vector drawables, and the Play Store
// PNGs (rasterised with headless Chrome). Run from anywhere: `node branding/generate.js`.
// Edit the geometry below, not the generated files.
const fs = require('fs'), cp = require('child_process'), path = require('path');

// Single source of truth for the Naqi mark: the 1024 master (the iOS app's AppIcon, verified against
// naqi/Assets.xcassets/AppIcon.appiconset/naqi-1024.png) mapped into the 108dp adaptive-icon viewport
// by one scale about the centre. The master square maps to 72 — exactly the 72dp the launcher shows —
// so the visible tile is the iOS icon, not a redraw of it. That makes the mark read a touch smaller
// here than Android icons usually do; matching the other platform is the deliberate trade.
const S = 72 / 1024;
const n = v => +(54 + (v - 512) * S).toFixed(2);   // master coordinate -> viewport
const w = v => +(v * S).toFixed(2);                // master length -> viewport

// The bowl of ن (nūn, first letter of نقي): a semicircle swept below the baseline.
const BOWL = `M${n(322)},${n(556)} A${w(190)},${w(190)} 0 0 0 ${n(702)},${n(556)}`;
const BOWL_W = w(112);
// The nūn's dot, drawn as a play triangle. Filled and stroked in one colour — the stroke is what
// rounds its corners.
const TRI = `M${n(446)},${n(232)} L${n(446)},${n(412)} L${n(614)},${n(322)} Z`;
const TRI_W = w(46);

const PAPER = '#F5F7F3', BRIGHT = '#6FD9B6';

// Mark bounding box in the viewport, strokes included.
const BB = {
  x0: n(322) - BOWL_W / 2, x1: n(702) + BOWL_W / 2,
  y0: n(232) - TRI_W / 2,  y1: n(556) + w(190) + BOWL_W / 2,
};

// The launcher crops to the centre 72dp and only the 66dp circle (r=33 from the centre) is
// guaranteed visible. Measure the three extremities rather than trusting the arithmetic above.
const reach = Math.max(
  Math.hypot(n(446) - 54, n(232) - 54) + TRI_W / 2,    // the triangle's top corner
  Math.abs(n(556) + w(190) - 54) + BOWL_W / 2,         // the bottom of the bowl
  Math.hypot(n(322) - 54, n(556) - 54) + BOWL_W / 2,   // the outer edge of the left cap
);
if (reach > 33) throw new Error(`mark reaches r=${reach.toFixed(1)}, outside the 66dp safe circle`);

const REPO = path.resolve(__dirname, '..');
const RES = REPO + '/app/src/main/res';
const BRAND = REPO + '/branding';
const TMP = __dirname + '/out';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
fs.mkdirSync(BRAND, { recursive: true });
fs.mkdirSync(TMP, { recursive: true });

// The master's gradient, spanning the 72dp the mask keeps rather than the whole canvas: padded
// beyond it, so the visible tile carries the master's full range instead of the muted middle of it.
const GRAD = `<linearGradient id="bg" x1="18" y1="18" x2="79.2" y2="90" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#1E6B58"/><stop offset="1" stop-color="#08110E"/>
  </linearGradient>`;

// The mark as SVG, two-tone or single-colour.
const markSvgPaths = (bowl, tri) =>
  `<path d="${BOWL}" fill="none" stroke="${bowl}" stroke-width="${BOWL_W}" stroke-linecap="round"/>
  <path d="${TRI}" fill="${tri}" stroke="${tri}" stroke-width="${TRI_W}" stroke-linejoin="round"/>`;

// ---------- SVG masters ----------
const logoSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <title>Naqi</title>
  <!-- Android adaptive-icon geometry: 108dp layer, launcher shows the centre 72dp,
       key shapes stay inside the 66dp safe circle (r=33 from 54,54). -->
  <defs>${GRAD}</defs>
  <rect width="108" height="108" fill="url(#bg)"/>
  ${markSvgPaths(PAPER, BRIGHT)}
</svg>
`;
// mark alone, tight box, single colour — for docs, favicons, anything monochrome
const box = [BB.x0 - 2, BB.y0 - 2, BB.x1 - BB.x0 + 4, BB.y1 - BB.y0 + 4].map(v => +v.toFixed(2));
const markSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${box.join(' ')}" width="${box[2]}" height="${box[3]}">
  <title>Naqi mark</title>
  ${markSvgPaths('currentColor', 'currentColor')}
</svg>
`;
fs.writeFileSync(BRAND + '/naqi-logo.svg', logoSvg);
fs.writeFileSync(BRAND + '/naqi-mark.svg', markSvg);

// ---------- Android vector drawables ----------
const vd = (body, extra = '') => `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"${extra}
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${body}</vector>
`;

// The same two paths as Android paths, indented to sit inside a <vector> or a <group>.
const markVdPaths = (bowl, tri, indent = '    ') => `${indent}<path
${indent}    android:pathData="${BOWL}"
${indent}    android:strokeColor="${bowl}"
${indent}    android:strokeWidth="${BOWL_W}"
${indent}    android:strokeLineCap="round" />
${indent}<path
${indent}    android:pathData="${TRI}"
${indent}    android:fillColor="${tri}"
${indent}    android:strokeColor="${tri}"
${indent}    android:strokeWidth="${TRI_W}"
${indent}    android:strokeLineJoin="round" />
`;

fs.writeFileSync(RES + '/drawable/ic_launcher_background.xml', vd(
`    <!-- The master's gradient, spanning the 72dp the mask keeps and clamped across the bleed, so the
         visible tile carries the whole range rather than the muted middle of it. -->
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="18"
                android:startY="18"
                android:endX="79.2"
                android:endY="90"
                android:tileMode="clamp">
                <item android:color="#FF1E6B58" android:offset="0" />
                <item android:color="#FF08110E" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
`, '\n    xmlns:aapt="http://schemas.android.com/aapt"'));

fs.writeFileSync(RES + '/drawable/ic_launcher_foreground.xml', vd(
`    <!-- The bowl of ن (nūn), first letter of نقي, whose dot is a play triangle. -->
${markVdPaths(`#FF${PAPER.slice(1)}`, `#FF${BRIGHT.slice(1)}`)}`));

// In-app / notification: same paths, scaled up to fill the frame, single colour so the call site
// can tint it.
const CY_M = +((BB.y0 + BB.y1) / 2).toFixed(2);
const SCALE = +(100 / (BB.y1 - BB.y0)).toFixed(2);          // fill the frame, 4 units of margin
if ((BB.x1 - BB.x0) * SCALE > 108) throw new Error('mark is too wide for the frame once scaled');
const markVd = (widthDp, tint) => `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${widthDp}dp"
    android:height="${widthDp}dp"
    android:viewportWidth="108"
    android:viewportHeight="108"${tint ? `\n    android:tint="${tint}"` : ''}>
    <!-- Shared with the launcher icon (ic_launcher_foreground); scaled to fill this frame. -->
    <group
        android:pivotX="54"
        android:pivotY="${CY_M}"
        android:scaleX="${SCALE}"
        android:scaleY="${SCALE}"
        android:translateY="${+(54 - CY_M).toFixed(2)}">
${markVdPaths('#FF000000', '#FF000000', '        ')}    </group>
</vector>
`;
fs.writeFileSync(RES + '/drawable/ic_naqi_mark.xml', markVd(24, null));
fs.writeFileSync(RES + '/drawable/ic_notification.xml', markVd(24, '#FFFFFFFF'));

// ---------- PNGs via headless Chrome ----------
// Only the store assets: minSdk is 29, so the launcher always reads the adaptive icon and the
// legacy density PNGs these used to generate were never loaded.
function png(out, size, shape) {
  const clip = shape === 'circle'
    ? `<circle cx="54" cy="54" r="36"/>`
    : shape === 'squircle'
    ? `<rect x="18" y="18" width="72" height="72" rx="16"/>`
    : `<rect x="18" y="18" width="72" height="72"/>`;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="${size}" height="${size}">
    <defs>${GRAD}<clipPath id="c">${clip}</clipPath></defs>
    <g clip-path="url(#c)"><rect width="108" height="108" fill="url(#bg)"/>
    ${markSvgPaths(PAPER, BRIGHT)}</g></svg>`;
  shot(svg, out, size, size);
}

// Rasterise a fragment of markup at an exact pixel size.
function shot(body, out, w, h) {
  const html = `${TMP}/t.html`;
  fs.writeFileSync(html, `<style>html,body{margin:0;padding:0;background:transparent}</style>${body}`);
  cp.execFileSync(CHROME, ['--headless', '--disable-gpu', '--hide-scrollbars',
    '--allow-file-access-from-files', '--force-device-scale-factor=1',
    '--default-background-color=00000000', `--screenshot=${out}`,
    `--window-size=${w},${h}`, `file://${html}`], { stdio: 'ignore' });
}

png(`${BRAND}/naqi-icon-512.png`, 512, 'square');   // Play Store listing icon
png(`${BRAND}/naqi-icon-1024.png`, 1024, 'squircle');

// ---------- Play feature graphic (1024x500) ----------
// Play crops this on some surfaces and overlays a play button over the middle, so the lockup sits
// left of centre and nothing that has to be read comes within 88px of an edge.
const FONT = `${RES}/font/thmanyah_sans`;
const MARK_H = 202, MARK_W = +(MARK_H * box[2] / box[3]).toFixed(1);
const AR_NAME = 'نقي';
const AR_TAG = 'فلترة فيديو حلال، '
  + 'على جهازك وحده.';
const banner = `<style>
  @font-face{font-family:Thmanyah;src:url('file://${FONT}_regular.otf');font-weight:400}
  @font-face{font-family:Thmanyah;src:url('file://${FONT}_medium.otf');font-weight:500}
  @font-face{font-family:Thmanyah;src:url('file://${FONT}_bold.otf');font-weight:700}
  body{width:1024px;height:500px;font-family:Thmanyah,sans-serif;color:${PAPER};overflow:hidden}
  .bg{position:absolute;inset:0;background:
      radial-gradient(70% 130% at 14% -10%, #2E9479 0%, rgba(46,148,121,0) 60%),
      linear-gradient(126deg, #1E6B58 0%, #0C1512 62%, #08110E 100%)}
  /* the mark again, oversized and bleeding off the corner — depth without a second colour */
  .ghost{display:none}
  .lockup{position:absolute;left:0;right:0;top:50%;transform:translateY(-50%);justify-content:center;
    display:flex;align-items:center;gap:50px}
  .mark{width:${MARK_W}px;height:${MARK_H}px;flex:none}
  h1{margin:0 0 22px;font-weight:700;font-size:92px;line-height:.86;letter-spacing:-2.5px;
    display:flex;align-items:baseline;gap:22px}
  h1 span{font-size:50px;letter-spacing:0;color:${BRIGHT}}
  p{margin:0;font-weight:500;font-size:29px;line-height:1.5;color:rgba(245,247,243,.74)}
  p+p{color:rgba(245,247,243,.5);font-size:27px}
  .keys{margin-top:30px;display:flex;gap:12px;font-weight:500;font-size:20px;letter-spacing:1.6px}
  .keys i{font-style:normal;padding:7px 15px;border:1px solid rgba(111,217,182,.34);
    border-radius:999px;color:${BRIGHT}}
</style>
<div class="bg"></div>
<svg class="ghost" viewBox="${box.join(' ')}" xmlns="http://www.w3.org/2000/svg">
  ${markSvgPaths(PAPER, PAPER)}
</svg>
<div class="lockup">
  <svg class="mark" viewBox="${box.join(' ')}" xmlns="http://www.w3.org/2000/svg">
    ${markSvgPaths(PAPER, BRIGHT)}
  </svg>
  <div>
    <h1>Naqi<span dir="rtl">${AR_NAME}</span></h1>
    <p>Halal video filtering, entirely on your phone.</p>
    <p dir="rtl">${AR_TAG}</p>
    <div class="keys"><i>PRIVATE</i><i>OFFLINE</i><i>OPEN SOURCE</i></div>
  </div>
</div>`;
shot(banner, `${BRAND}/naqi-feature-1024x500.png`, 1024, 500);

fs.rmSync(TMP, { recursive: true, force: true });
console.log('generated');
