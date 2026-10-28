package com.ollie.netcrawler;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public final class NetInfo {

    public static String autoCidr(Context ctx) {
        try {
            for (Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                 ifs.hasMoreElements(); ) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address) {
                        int pfx = ia.getNetworkPrefixLength();
                        if (pfx <= 0 || pfx > 30) pfx = 24;
                        byte[] b = a.getAddress();
                        int ip = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
                        int mask = pfx == 32 ? -1 : (-1 << (32 - pfx));
                        int net = ip & mask;
                        return ((net >>> 24) & 0xff) + "." + ((net >>> 16) & 0xff)
                                + "." + ((net >>> 8) & 0xff) + "." + (net & 0xff) + "/" + pfx;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static String summary(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo wi = wm.getConnectionInfo();
        if (wi == null) return "no wifi";
        return "ssid=" + unquote(wi.getSSID())
                + " bssid=" + wi.getBSSID()
                + " rssi=" + wi.getRssi() + "dBm"
                + " link=" + wi.getLinkSpeed() + "Mbps";
    }

    private static String unquote(String s) {
        if (s == null) return "?";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1);
        return s;
    }
}
