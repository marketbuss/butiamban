// gadgetforge mod.js — generated friends-only preset
const GADGETFORGE_PRESET = "friends-only";
const GADGETFORGE_OFFSETS = {};

/*
 * gadgetforge mod.js v2 — generic Unity il2cpp mod runtime
 *
 * The workflow prepends two globals before this file at build time:
 *   const GADGETFORGE_PRESET  = "safe-public" | "friends-only" | "solo-godmode";
 *   const GADGETFORGE_OFFSETS = { PlayerMovement_FixedUpdate: "0x...", ... };
 *
 * Features
 *   - 3 presets with sane toggles per lobby type
 *   - hot-reload: writes config to /sdcard/Download/animalcompany.cfg on
 *     first launch, re-reads it every 2 seconds — edit it on-device (any
 *     file manager apk) and the mod picks up changes without reinstall
 *   - verbose logs to logcat tag "gadget"
 *   - safe no-op when an offset is zero or missing
 */
'use strict';

const TAG = '[gadget]';
const CFG_PATH = '/sdcard/Download/animalcompany.cfg';

const PRESETS = {
    'safe-public': {
        speed: 1.0, godMode: false, infiniteMoney: false,
        esp: true, noFallDamage: true, noclip: false,
        packetLog: false, speedCap: 1.3,
    },
    'friends-only': {
        speed: 2.0, godMode: true, infiniteMoney: false,
        esp: true, noFallDamage: true, noclip: false,
        packetLog: true, speedCap: 5.0,
    },
    'solo-godmode': {
        speed: 3.0, godMode: true, infiniteMoney: true,
        esp: true, noFallDamage: true, noclip: true,
        packetLog: true, speedCap: 10.0,
    },
};

let CFG = Object.assign({}, PRESETS[typeof GADGETFORGE_PRESET !== 'undefined'
    ? GADGETFORGE_PRESET : 'friends-only']);
const OFFSETS = typeof GADGETFORGE_OFFSETS !== 'undefined' ? GADGETFORGE_OFFSETS : {};

const log = (...a) => { console.log(TAG, ...a); };

Java.perform(function () {
    log('attach pid=', Process.id, 'preset=',
        typeof GADGETFORGE_PRESET !== 'undefined' ? GADGETFORGE_PRESET : '?');
    log('offsets:', JSON.stringify(OFFSETS));
    log('initial cfg:', JSON.stringify(CFG));

    // write out current config on first boot so olivia can edit it on-device
    try {
        const File = Java.use('java.io.File');
        const FOS = Java.use('java.io.FileOutputStream');
        const f = File.$new(CFG_PATH);
        if (!f.exists()) {
            const out = FOS.$new(f);
            const bytes = Java.use('java.lang.String').$new(JSON.stringify(CFG, null, 2))
                            .getBytes();
            out.write(bytes);
            out.close();
            log('config seeded at', CFG_PATH);
        }
    } catch (e) { log('cfg seed err', e); }

    // hot-reload: re-read cfg every 2s and apply changes
    setInterval(function () {
        Java.perform(function () {
            try {
                const File = Java.use('java.io.File');
                const FIS = Java.use('java.io.FileInputStream');
                const f = File.$new(CFG_PATH);
                if (!f.exists()) return;
                const sz = f.length();
                if (sz <= 0 || sz > 8192) return;
                const fis = FIS.$new(f);
                const buf = Java.array('byte', new Array(sz).fill(0));
                fis.read(buf);
                fis.close();
                const txt = Java.use('java.lang.String').$new(buf, 'UTF-8');
                const next = JSON.parse(txt);
                const old = JSON.stringify(CFG);
                CFG = Object.assign({}, CFG, next);
                if (JSON.stringify(CFG) !== old) log('cfg reload →', JSON.stringify(CFG));
            } catch (e) { /* ignore malformed edits */ }
        });
    }, 2000);

    // il2cpp hooks: poll until libil2cpp.so is loaded
    let tries = 0;
    const il2wait = setInterval(() => {
        const m = Process.findModuleByName('libil2cpp.so');
        if (m) {
            clearInterval(il2wait);
            log('libil2cpp.so base=', m.base);
            wireIl2Hooks(m);
        } else if (++tries > 60) {
            clearInterval(il2wait);
            log('no libil2cpp — not Unity il2cpp');
        }
    }, 250);
});

function offsetPtr(base, key) {
    const o = OFFSETS[key];
    if (!o || o === '0x0' || o === '0x00' || o === '') return null;
    return base.add(ptr(o));
}

function wireIl2Hooks(il2) {
    const BASE = il2.base;

    // speed hack — scales horizontal velocity
    const pmFU = offsetPtr(BASE, 'PlayerMovement_FixedUpdate');
    if (pmFU) {
        Interceptor.attach(pmFU, {
            onEnter(args) { this.self = args[0]; },
            onLeave() {
                if (!this.self || CFG.speed <= 1.001) return;
                const k = Math.min(CFG.speed, CFG.speedCap || 10);
                // velocity Vector3 commonly at offset 0x40 — adjust per game
                try {
                    const vp = this.self.add(0x40);
                    vp.writeFloat(vp.readFloat() * k);
                    vp.add(8).writeFloat(vp.add(8).readFloat() * k);
                } catch (e) {}
            }
        });
        log('speed hook wired');
    }

    // infinite money
    const sm = offsetPtr(BASE, 'Economy_SetMoney');
    if (sm) {
        Interceptor.attach(sm, {
            onEnter(args) {
                if (CFG.infiniteMoney) args[1] = ptr(999999);
            }
        });
        log('money hook wired');
    }

    // god mode: short-circuit damage
    const dmg = offsetPtr(BASE, 'Player_TakeDamage');
    if (dmg) {
        Interceptor.attach(dmg, {
            onEnter() { this.skip = CFG.godMode; },
            onLeave(r) { if (this.skip) r.replace(ptr(0)); }
        });
        log('damage hook wired');
    }

    // ESP marker — attached to Camera.Render, actual depth-test flip
    // happens in game-side material; this is the hook point
    const cr = offsetPtr(BASE, 'Camera_Render');
    if (cr) {
        Interceptor.attach(cr, {
            onEnter() { /* iterate renderers, set material._ZTest = Always */ }
        });
        log('camera render hook wired (plug ESP body here)');
    }

    // packet logger
    const net = offsetPtr(BASE, 'GameNet_SendPacket');
    if (net) {
        Interceptor.attach(net, {
            onEnter(args) {
                if (!CFG.packetLog) return;
                const len = args[2].toInt32();
                if (len <= 0 || len > 256) return;
                const bytes = args[1].readByteArray(Math.min(len, 128));
                log('tx', len, 'B', bytes);
            }
        });
        log('packet tx hook wired');
    }

    // stamina drain neutraliser
    const st = offsetPtr(BASE, 'Stamina_Decrement');
    if (st) {
        Interceptor.replace(st,
            new NativeCallback(function () { return; }, 'void', ['pointer', 'float']));
        log('stamina drain neutralised');
    }

    log('all available hooks wired. edit', CFG_PATH, 'to retune live.');
}

