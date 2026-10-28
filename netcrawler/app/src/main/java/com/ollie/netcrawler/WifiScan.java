package com.ollie.netcrawler;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WifiScan {

    public static List<String> results(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            List<ScanResult> raw = wm.getScanResults();
            Collections.sort(raw, (a, b) -> b.level - a.level);
            for (ScanResult r : raw) {
                out.add(String.format("%-24s  bssid=%s  rssi=%ddBm  ch=%d  caps=%s",
                        r.SSID == null ? "<hidden>" : r.SSID,
                        r.BSSID, r.level, chan(r.frequency),
                        caps(r.capabilities)));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static int chan(int freq) {
        if (freq >= 2412 && freq <= 2484) return (freq == 2484) ? 14 : (freq - 2407) / 5;
        if (freq >= 5000 && freq < 6000) return (freq - 5000) / 5;
        if (freq >= 5955) return (freq - 5950) / 5;
        return 0;
    }

    private static String caps(String s) {
        if (s == null) return "?";
        StringBuilder sb = new StringBuilder();
        if (s.contains("WPA3")) sb.append("WPA3 ");
        else if (s.contains("WPA2")) sb.append("WPA2 ");
        else if (s.contains("WPA"))  sb.append("WPA ");
        else if (s.contains("WEP"))  sb.append("WEP ");
        else sb.append("OPEN ");
        if (s.contains("CCMP")) sb.append("CCMP ");
        if (s.contains("TKIP")) sb.append("TKIP ");
        if (s.contains("PSK"))  sb.append("PSK ");
        if (s.contains("SAE"))  sb.append("SAE ");
        if (s.contains("EAP"))  sb.append("EAP ");
        if (s.contains("WPS"))  sb.append("WPS! ");
        return sb.toString().trim();
    }
}
