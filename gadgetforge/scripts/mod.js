/*
 * mod.js — gadget-side runtime hooks for the patched target apk
 *
 * This file is loaded by frida-gadget *inside* the target process at
 * launch time when the apk is patched with workflow input
 * `embed_script=true`. It can also be used from the host:
 *   adb forward tcp:27042 tcp:27042
 *   frida -U -n gadget -l gadgetforge/scripts/mod.js
 *
 * The example below shows the generic patterns used to mod a Unity
 * il2cpp game (Animal Company is one). Replace the Il2Cpp offsets
 * once you dump them with Il2CppDumper for your specific game version.
 */

'use strict';

const log = (...a) => console.log('[gadget]', ...a);

Java.perform(function () {
    log('attached, pid=', Process.id, 'threads=', Process.enumerateThreads().length);

    // ---- 1. generic Java hooks (work on anything using the Java API) ----

    // print every Application.onCreate so we know when the game is fully up
    const App = Java.use('android.app.Application');
    App.onCreate.implementation = function () {
        log('Application.onCreate for', this.getPackageName());
        this.onCreate();
    };

    // ---- 2. native-side il2cpp hooks (Unity games) ----
    // find libil2cpp.so once it is mapped
    const tryIl2Cpp = () => {
        const m = Process.findModuleByName('libil2cpp.so');
        if (!m) return null;
        log('libil2cpp.so base=', m.base, 'size=', m.size);
        return m;
    };

    // poll until libil2cpp.so is mapped (Unity loads it a bit after launch)
    let tries = 0;
    const timer = setInterval(() => {
        const il2 = tryIl2Cpp();
        if (il2 || ++tries > 40) {
            clearInterval(timer);
            if (il2) wireHooks(il2);
            else log('libil2cpp.so never mapped — not a Unity game?');
        }
    }, 250);

    function wireHooks(il2) {
        // ------------------------------------------------------------
        // REPLACE THESE OFFSETS with the ones you dumped with
        // Il2CppDumper (dump.cs + script.json). Example symbols:
        // ------------------------------------------------------------
        const OFFSETS = {
            PlayerMovement_FixedUpdate: 0x0,  // 0x012A3B40,
            Economy_SetMoney:           0x0,  // 0x009B22A0,
            Player_TakeDamage:          0x0,  // 0x011ACF10,
            Camera_Render:              0x0,  // 0x00AA4580,
            GameNet_SendPacket:         0x0,  // 0x01339C00,
        };

        // --- speed hack: scale velocity in FixedUpdate ---
        if (OFFSETS.PlayerMovement_FixedUpdate) {
            Interceptor.attach(il2.base.add(OFFSETS.PlayerMovement_FixedUpdate), {
                onEnter(args) { this.self = args[0]; },
                onLeave(_r) {
                    // read the Vector3 velocity field at offset 0x40 (example)
                    // then write it back scaled x2
                    const vxPtr = this.self.add(0x40);
                    const vx = vxPtr.readFloat();
                    const vy = vxPtr.add(4).readFloat();
                    const vz = vxPtr.add(8).readFloat();
                    const k = 2.0;
                    vxPtr.writeFloat(vx * k);
                    vxPtr.add(4).writeFloat(vy);  // keep gravity sane
                    vxPtr.add(8).writeFloat(vz * k);
                }
            });
            log('speed hack x2 wired on PlayerMovement_FixedUpdate');
        }

        // --- infinite money / bananas: force the setter arg ---
        if (OFFSETS.Economy_SetMoney) {
            Interceptor.attach(il2.base.add(OFFSETS.Economy_SetMoney), {
                onEnter(args) { args[1] = ptr(999999); }
            });
            log('money setter clamped to 999999');
        }

        // --- god mode: skip damage ---
        if (OFFSETS.Player_TakeDamage) {
            Interceptor.replace(il2.base.add(OFFSETS.Player_TakeDamage),
                new NativeCallback(function () { return; }, 'void', ['pointer', 'float']));
            log('damage path neutralised');
        }

        // --- ESP via Camera.Render: force depth test off on known mats ---
        if (OFFSETS.Camera_Render) {
            Interceptor.attach(il2.base.add(OFFSETS.Camera_Render), {
                onEnter() { /* flip _ZTest=Always on cached materials here */ }
            });
            log('camera render hooked (populate ESP in onEnter)');
        }

        // --- packet logger ---
        if (OFFSETS.GameNet_SendPacket) {
            Interceptor.attach(il2.base.add(OFFSETS.GameNet_SendPacket), {
                onEnter(args) {
                    const len = args[2].toInt32();
                    const bytes = args[1].readByteArray(Math.min(len, 128));
                    log('tx', len, 'bytes', bytes);
                }
            });
            log('packet tx logger wired');
        }
    }
});
