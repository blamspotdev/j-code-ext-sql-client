// Production build for the VM Manager extension.
// Type-checks with tsc, then bundles + minifies TypeScript/CSS into the deployable www/.
// `jext pack` runs this (npm run build) before packaging; www/ is the only output that ships.
import { build } from 'esbuild';
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, rmSync } from 'node:fs';

const OUT = 'www';
const win = process.platform === 'win32';

// 1. Type-check (fails the build on any TS error — this is a production build).
const tc = spawnSync('npx', ['tsc', '--noEmit'], { stdio: 'inherit', shell: win });
if (tc.status !== 0) process.exit(tc.status || 1);

// 2. Fresh output dir.
rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

// 3. Bundle + minify the app and styles.
await build({
  entryPoints: ['src/main.ts'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'es2019',
  legalComments: 'none',
  outfile: `${OUT}/main.js`,
});
await build({
  entryPoints: ['src/styles.css'],
  bundle: true,
  minify: true,
  outfile: `${OUT}/styles.css`,
});

// 4. Ship the HTML shell verbatim (it references ./styles.css and ./main.js).
copyFileSync('src/index.html', `${OUT}/index.html`);

console.log('✓ built src/ → www/ (index.html, main.js, styles.css)');
