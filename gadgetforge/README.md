# gadgetforge

inject frida-gadget into any Android apk without root — Quest 3 ready.

## quick path

1. drop your target apk at `gadgetforge/input/target.apk` (git add + push to
   `claude/ollie-bench-deployment-u4k02l`) **or** trigger the workflow
   manually with `apk_url` pointing at any public URL (gist, transfer.sh,
   release asset, etc.).

2. GitHub Actions:
   - pulls the latest `frida-gadget` arm64-v8a release from frida/frida
   - runs `objection patchapk` (sensepost/objection) on your apk —
     decompiles with apktool, drops the gadget `.so` into `lib/arm64-v8a/`,
     injects a `System.loadLibrary("frida-gadget")` smali hook so the
     gadget loads before `Application.onCreate`
   - zipaligns and signs with a freshly generated debug keystore
   - pushes the patched apk to the `apk-builds` branch under
     `build/gadgetforge/target.gadget.apk`

3. pull it:
   ```
   git fetch origin apk-builds
   git show origin/apk-builds:build/gadgetforge/target.gadget.apk > target.gadget.apk
   ```

4. install on the Quest 3 (dev mode on, usb-c, allow usb debugging):
   ```
   adb uninstall com.your.gameOriginalPackageId
   adb install -r target.gadget.apk
   ```

5. launch the app on the headset. gadget starts listening on
   `127.0.0.1:27042`.

6. attach from your dev machine:
   ```
   adb forward tcp:27042 tcp:27042
   frida -U -n gadget -l gadgetforge/scripts/mod.js
   ```

   or re-run the workflow with `embed_script=true` to bake
   `gadgetforge/scripts/mod.js` directly into the patched apk — the
   gadget auto-loads it on launch, no host attach needed.

## notes

- the Meta Store build is signed by Meta. your patched build is signed by
  the gadgetforge debug key, so android refuses an in-place update.
  uninstall the Store version first (your saves on Animal Company are
  mostly server/cloud, local state is light).

- gadget version is pinned to whatever `frida/frida` releases as
  `latest` — same major version client-side (your `frida` CLI) is
  required to talk to it.

- the smali injection targets the first Activity declared with
  `MAIN/LAUNCHER`. if the apk uses a custom `Application` subclass,
  objection prefers that path (loads even earlier, before any Activity).

- for Unity il2cpp games (Animal Company), dump offsets once per game
  version with Il2CppDumper against `libil2cpp.so` + `global-metadata.dat`
  from the original apk; then write hooks against those offsets in
  `scripts/mod.js`.
