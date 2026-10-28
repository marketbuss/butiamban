# netcrawler — build & sideload

## bake the apk (any machine with internet to google maven)

```bash
cd netcrawler
# gradle wrapper (one-time, needs network to services.gradle.org — already allowed by google maven)
gradle wrapper --gradle-version 8.5
./gradlew assembleDebug
# apk lands here:
ls -la app/build/outputs/apk/debug/app-debug.apk
```

requires: JDK 17+, Android SDK with platform-34 and build-tools-34.0.0.
set once: `export ANDROID_HOME=~/Android/Sdk` (or wherever).

## push to the quest 3

1. enable developer mode on the headset (Meta Horizon mobile app → devices → quest → developer mode on)
2. plug it into usb-c, accept the "allow usb debugging" prompt inside the headset
3. install:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

4. launch: Library → Unknown Sources → netcrawler
