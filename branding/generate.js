// Regenerates every Naqi logo asset — SVG masters, Android vector drawables, and the launcher /
// Play Store PNGs (rasterised with headless Chrome). Run from anywhere: `node branding/generate.js`.
// Edit the CUP/DROP parameters below, not the generated files.
const fs = require('fs'), cp = require('child_process'), path = require('path');

// Single source of truth for the Naqi mark geometry, drawn in the 108dp adaptive-icon viewport.
// Everything (launcher, Play Store, in-app, notification) is generated from these two paths.
const K = 0.62;                 // bezier handle ratio — slightly squarer than a true circle (0.5523)
const CX = 54, CY = 54;

// Nun bowl (ن) as a vessel: symmetric geometric cup with a slanted lip.
function cup({ W, T, top, bot, cs, slant }) {
  const iW = W - T, ib = bot - T, csi = cs - 2, n = x => +x.toFixed(2);
  return `M${n(CX-W)},${top} L${n(CX-W)},${cs} `
    + `C${n(CX-W)},${n(cs+(bot-cs)*K)} ${n(CX-W*K)},${bot} ${CX},${bot} `
    + `C${n(CX+W*K)},${bot} ${n(CX+W)},${n(cs+(bot-cs)*K)} ${n(CX+W)},${cs} `
    + `L${n(CX+W)},${top} L${n(CX+iW)},${n(top+slant)} L${n(CX+iW)},${csi} `
    + `C${n(CX+iW)},${n(csi+(ib-csi)*K)} ${n(CX+iW*K)},${ib} ${CX},${ib} `
    + `C${n(CX-iW*K)},${ib} ${n(CX-iW)},${n(csi+(ib-csi)*K)} ${n(CX-iW)},${csi} `
    + `L${n(CX-iW)},${n(top+slant)} Z`;
}

// The nun's dot, drawn as a droplet — same silhouette as NaqiIcons.Droplet in the app.
function drop(cx, ty, h) {
  const s = h / 19.3, X = x => +(cx + (x-12)*s).toFixed(2), Y = y => +(ty + (y-2)*s).toFixed(2);
  return `M${X(12)},${Y(2)} C${X(9.5)},${Y(5.5)} ${X(5)},${Y(9.8)} ${X(5)},${Y(14.3)} `
    + `C${X(5)},${Y(18.2)} ${X(8.1)},${Y(21.3)} ${X(12)},${Y(21.3)} `
    + `C${X(15.9)},${Y(21.3)} ${X(19)},${Y(18.2)} ${X(19)},${Y(14.3)} `
    + `C${X(19)},${Y(9.8)} ${X(14.5)},${Y(5.5)} ${X(12)},${Y(2)} Z`;
}

// Fitted so the whole mark stays inside the 66dp safe circle (r=33 from centre) with room to
// breathe — the furthest point is the lip corner at r≈29.7. Sits ~1dp low: the bowl carries the
// mass, so a geometrically centred mark reads bottom-heavy.
const CUP  = cup({ W: 23.8, T: 9.7, top: 36.2, bot: 78.4, cs: 52, slant: 4 });
const DROP = drop(54, 31.8, 21.6);
const HALO = drop(54, 29.3, 26.4);   // grown copy, knocked out of the cup for an even gap

const PAPER = "#F5F7F3", BRIGHT = "#55C3A1", JADE = "#1F6E5A";



const REPO = path.resolve(__dirname, '..');
const RES = REPO + '/app/src/main/res';
const BRAND = REPO + '/branding';
const TMP = __dirname + '/out';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
fs.mkdirSync(BRAND, { recursive: true });
fs.mkdirSync(TMP, { recursive: true });

const GRAD = `<radialGradient id="bg" cx="54" cy="36" r="80" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#24805F"/><stop offset=".55" stop-color="#123A2F"/><stop offset="1" stop-color="#08110E"/>
  </radialGradient>`;

// ---------- SVG masters ----------
const logoSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <title>Naqi</title>
  <!-- Android adaptive-icon geometry: 108dp layer, launcher shows the centre 72dp,
       key shapes stay inside the 66dp safe circle (r=33 from 54,54). -->
  <defs>${GRAD}</defs>
  <rect width="108" height="108" fill="url(#bg)"/>
  <path d="${CUP}" fill="${PAPER}"/>
  <path d="${DROP}" fill="${BRIGHT}"/>
</svg>
`;
// mark alone, tight box, single colour — for docs, favicons, anything monochrome
const markSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="28 29.8 52 50.6" width="52" height="50.6">
  <title>Naqi mark</title>
  <path d="${CUP}" fill="currentColor"/>
  <path d="${DROP}" fill="currentColor"/>
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

fs.writeFileSync(RES + '/drawable/ic_launcher_background.xml', vd(
`    <!-- Depth of clean water, lit from where the drop lands. -->
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="36"
                android:gradientRadius="80">
                <item android:color="#FF24805F" android:offset="0" />
                <item android:color="#FF123A2F" android:offset="0.55" />
                <item android:color="#FF08110E" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
`, '\n    xmlns:aapt="http://schemas.android.com/aapt"'));

fs.writeFileSync(RES + '/drawable/ic_launcher_foreground.xml', vd(
`    <!-- The bowl of ن (nūn), first letter of نقي, drawn as a vessel. -->
    <path
        android:fillColor="#FFF5F7F3"
        android:pathData="${CUP}" />
    <!-- The nūn's dot, drawn as a droplet — the same silhouette as NaqiIcons.Droplet. -->
    <path
        android:fillColor="#FF55C3A1"
        android:pathData="${DROP}" />
`));

// In-app / notification: same paths, scaled up to fill the frame, single colour so the call site
// can tint it. Mark bbox is x 30.15..77.85, y 31.8..78.4 -> centre (54, 55.1).
const markVd = (widthDp, tint) => `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${widthDp}dp"
    android:height="${widthDp}dp"
    android:viewportWidth="108"
    android:viewportHeight="108"${tint ? `\n    android:tint="${tint}"` : ''}>
    <!-- Shared with the launcher icon (ic_launcher_foreground); scaled to fill this frame. -->
    <group
        android:pivotX="54"
        android:pivotY="55.1"
        android:scaleX="2.1"
        android:scaleY="2.1"
        android:translateY="-1.1">
        <path
            android:fillColor="#FF000000"
            android:pathData="${CUP}" />
        <path
            android:fillColor="#FF000000"
            android:pathData="${DROP}" />
    </group>
</vector>
`;
fs.writeFileSync(RES + '/drawable/ic_naqi_mark.xml', markVd(24, null));
fs.writeFileSync(RES + '/drawable/ic_notification.xml', markVd(24, '#FFFFFFFF'));

// ---------- PNGs via headless Chrome ----------
// Legacy raster icons are pre-masked, so render the 72dp window the launcher actually shows.
function png(out, size, shape) {
  const clip = shape === 'circle'
    ? `<circle cx="54" cy="54" r="36"/>`
    : shape === 'squircle'
    ? `<rect x="18" y="18" width="72" height="72" rx="16"/>`
    : `<rect x="18" y="18" width="72" height="72"/>`;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="${size}" height="${size}">
    <defs>${GRAD}<clipPath id="c">${clip}</clipPath></defs>
    <g clip-path="url(#c)"><rect width="108" height="108" fill="url(#bg)"/>
    <path d="${CUP}" fill="${PAPER}"/><path d="${DROP}" fill="${BRIGHT}"/></g></svg>`;
  const html = `${TMP}/t.html`;
  fs.writeFileSync(html, `<style>html,body{margin:0;padding:0;background:transparent}</style>${svg}`);
  cp.execFileSync(CHROME, ['--headless', '--disable-gpu', '--hide-scrollbars',
    '--default-background-color=00000000', `--screenshot=${out}`,
    `--window-size=${size},${size}`, `file://${html}`], { stdio: 'ignore' });
}

const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [d, s] of Object.entries(DENSITIES)) {
  png(`${RES}/mipmap-${d}/ic_launcher.png`, s, 'squircle');
  png(`${RES}/mipmap-${d}/ic_launcher_round.png`, s, 'circle');
  fs.rmSync(`${RES}/mipmap-${d}/ic_launcher.webp`, { force: true });
  fs.rmSync(`${RES}/mipmap-${d}/ic_launcher_round.webp`, { force: true });
}
png(`${BRAND}/naqi-icon-512.png`, 512, 'square');   // Play Store listing icon
png(`${BRAND}/naqi-icon-1024.png`, 1024, 'squircle');
fs.rmSync(TMP, { recursive: true, force: true });
console.log('generated');
