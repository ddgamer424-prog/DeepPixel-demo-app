#!/usr/bin/env node
// Copyright (c) 2026 DDgamer. All rights reserved.
// Creates the Android project, wires in the native code, and (with --build) produces the APK.
const fs = require('fs'), path = require('path'), cp = require('child_process');
const root = path.basename(__dirname) === 'scripts' ? path.resolve(__dirname, '..') : __dirname, droid = path.join(root, 'android'), PKG = 'com.deeppixel.app';
const win = process.platform === 'win32';

const run = (cmd, cwd = root) => {
  console.log('\n> ' + cmd);
  if (cp.spawnSync(cmd, { cwd, stdio: 'inherit', shell: true }).status !== 0) { console.error('\nFailed: ' + cmd); process.exit(1); }
};
const patch = (file, fn) => {
  const s = fs.readFileSync(file, 'utf8'), o = fn(s);
  if (o === s) console.log('  already patched: ' + path.relative(root, file));
  else { fs.writeFileSync(file, o); console.log('  patched: ' + path.relative(root, file)); }
};

// Phone-friendly: if files were uploaded flat (no folders), put them where the project expects them.
const mv = (f, d) => { const s = path.join(root, f); if (fs.existsSync(s)) { fs.mkdirSync(path.join(root, d), { recursive: true }); fs.renameSync(s, path.join(root, d, f)); } };
['index.html', 'logo.png'].forEach(f => mv(f, 'www'));
['DeepPixelPlugin.kt', 'ServerService.kt', 'tagfix.c'].forEach(f => mv(f, 'native'));
['icon-only.png', 'icon-foreground.png', 'icon-background.png', 'splash.png', 'splash-dark.png'].forEach(f => mv(f, 'assets'));

run('npm install');
if (!fs.existsSync(droid)) run('npx cap add android');

console.log('\nAdding DeepPixel native code…');
const jdir = path.join(droid, 'app/src/main/java', ...PKG.split('.'));
fs.mkdirSync(jdir, { recursive: true });
for (const f of ['DeepPixelPlugin.kt', 'ServerService.kt']) fs.copyFileSync(path.join(root, 'native', f), path.join(jdir, f));
fs.writeFileSync(path.join(jdir, 'MainActivity.java'), `package ${PKG};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DeepPixelPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);

// Signing key: shipped as text (.b64) so uploads can't corrupt it; decoded here for every build
const b64 = path.join(root, 'deeppixel.keystore.b64');
if (fs.existsSync(b64)) fs.writeFileSync(path.join(root, 'deeppixel.keystore'), Buffer.from(fs.readFileSync(b64, 'utf8').replace(/\s+/g, ''), 'base64'));

// Native shim (native/tagfix.c): switches off Android's heap pointer tagging inside the Java process
function findClang() {
  const homes = [process.env.ANDROID_NDK_HOME, process.env.ANDROID_NDK_ROOT, process.env.ANDROID_NDK_LATEST_HOME];
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (sdk && fs.existsSync(path.join(sdk, 'ndk')))
    fs.readdirSync(path.join(sdk, 'ndk')).sort().reverse().forEach(d => homes.push(path.join(sdk, 'ndk', d)));
  for (const h of homes.filter(Boolean)) {
    const pre = path.join(h, 'toolchains/llvm/prebuilt');
    if (!fs.existsSync(pre)) continue;
    for (const host of fs.readdirSync(pre)) {
      const c = path.join(pre, host, 'bin', 'aarch64-linux-android26-clang' + (win ? '.cmd' : ''));
      if (fs.existsSync(c)) return c;
    }
  }
  return null;
}
const clang = findClang();
if (clang) {
  const out = path.join(droid, 'app/src/main/jniLibs/arm64-v8a');
  fs.mkdirSync(out, { recursive: true });
  run(`"${clang}" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -o "${path.join(out, 'libtagfix.so')}" "${path.join(root, 'native/tagfix.c')}"`);
} else {
  console.error('\nERROR: Android NDK not found, so the Java crash fix cannot be built. Install the NDK (Android Studio > SDK Manager > SDK Tools > NDK) or set ANDROID_NDK_HOME.');
  if (process.env.CI) process.exit(1);
}

// Manifest: permissions, foreground service, and switch off Android's pointer tagging (it crashes Java)
patch(path.join(droid, 'app/src/main/AndroidManifest.xml'), s => {
  if (!s.includes('ServerService')) {
    const perms = ['FOREGROUND_SERVICE', 'WAKE_LOCK', 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'POST_NOTIFICATIONS',
                   'MANAGE_EXTERNAL_STORAGE', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE']
      .map(p => `    <uses-permission android:name="android.permission.${p}" />`).join('\n');
    s = s.replace('</application>', '    <service android:name=".ServerService" android:exported="false" />\n    </application>')
         .replace('</manifest>', perms + '\n</manifest>');
  }
  if (!s.includes('allowNativeHeapPointerTagging'))
    s = s.replace('<application', '<application android:allowNativeHeapPointerTagging="false" android:memtagMode="off" android:requestLegacyExternalStorage="true"');
  return s;
});

// Kotlin support (the plugin is written in Kotlin)
patch(path.join(droid, 'build.gradle'), s => s.includes('kotlin-gradle-plugin') ? s :
  s.replace(/(classpath\s+['"]com\.android\.tools\.build:gradle:[^'"]+['"])/, "$1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24'"));
patch(path.join(droid, 'app/build.gradle'), s => {
  if (!s.includes('kotlin-android'))
    s = s.replace("apply plugin: 'com.android.application'", "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'")
         .replace(/android\s*\{/, "android {\n    kotlinOptions { jvmTarget = '17' }");
  if (!s.includes('useLegacyPackaging'))
    s = s.replace(/android\s*\{/, 'android {\n    packaging { jniLibs { useLegacyPackaging = true } }');
  // Same signing key every build, so updates install over the old app (keeps your servers and Java)
  if (!s.includes('deeppixel.keystore') && fs.existsSync(path.join(root, 'deeppixel.keystore')))
    s = s.replace(/android\s*\{/, 'android {\n    signingConfigs { debug { storeFile file("../../deeppixel.keystore"); storePassword "deeppixel"; keyAlias "deeppixel"; keyPassword "deeppixel" } }');
  return s;
});

// SDK levels: minSdk 26 (foreground service APIs); targetSdk 28 so Android lets the app run the Java runtime from its own storage
patch(path.join(droid, 'variables.gradle'), s =>
  s.replace(/minSdkVersion\s*=\s*\d+/, 'minSdkVersion = 26').replace(/targetSdkVersion\s*=\s*\d+/, 'targetSdkVersion = 28'));

run('npx @capacitor/assets generate --android');
run('npx cap sync android');

if (process.argv.includes('--build')) {
  const gw = win ? 'gradlew.bat' : './gradlew';
  if (!win) fs.chmodSync(path.join(droid, 'gradlew'), 0o755);
  if (!process.env.ANDROID_HOME && !process.env.ANDROID_SDK_ROOT && !fs.existsSync(path.join(droid, 'local.properties')))
    console.warn('\nWarning: Android SDK not found. Install Android Studio or set ANDROID_HOME.');
  run(`${gw} assembleDebug`, droid);
  console.log('\nDone! Your APK: android/app/build/outputs/apk/debug/app-debug.apk');
} else {
  console.log('\nProject ready. Build the APK with:  npm run build:apk   (or open the android/ folder in Android Studio)');
}
