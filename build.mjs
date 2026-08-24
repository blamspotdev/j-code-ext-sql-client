// Production build for the SQL Client extension.
//
// What ships is one file: `lib/sqlstudio.dex`, the extension's own code, drawn by JCode's Compose
// runtime inside JCode's process. `jext pack` runs this (npm run build) before packaging.
//
// The dex is taken from the merge task's output rather than unzipped back out of the APK the Android
// plugin builds around it: the APK is a by-product here — this plugin owns no resources, so there is
// no resource table for JCode to attach and nothing else in the archive worth keeping.
//
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const win = process.platform === 'win32';
// Absolute, because a bare `gradlew.bat` is not found in the working directory the way `./gradlew`
// is on a POSIX shell, and the two platforms disagree about which relative spelling works.
const gradlew = resolve('studio', win ? 'gradlew.bat' : 'gradlew');
const DEX = 'studio/build/intermediates/dex/release/mergeDexRelease/classes.dex';

const build = spawnSync(gradlew, ['assembleRelease'], {
  cwd: 'studio',
  stdio: 'inherit',
  shell: win,
});
if (build.status !== 0) process.exit(build.status || 1);

mkdirSync('lib', { recursive: true });
copyFileSync(DEX, 'lib/sqlstudio.dex');

console.log('✓ built studio/ → lib/sqlstudio.dex');
