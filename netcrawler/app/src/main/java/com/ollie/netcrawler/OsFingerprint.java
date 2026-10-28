package com.ollie.netcrawler;

import java.net.InetAddress;

public final class OsFingerprint {

    public static String guess(String ip, int timeoutMs) {
        try {
            long t0 = System.nanoTime();
            boolean up = InetAddress.getByName(ip).isReachable(timeoutMs);
            if (!up) return "";
            long rtt = (System.nanoTime() - t0) / 1_000_000L;
            String note = "rtt=" + rtt + "ms";
            String rev = reverse(ip);
            if (!rev.isEmpty()) note += "  ptr=" + rev;
            return note;
        } catch (Throwable t) { return ""; }
    }

    public static String reverse(String ip) {
        try {
            String h = InetAddress.getByName(ip).getCanonicalHostName();
            return h.equals(ip) ? "" : h;
        } catch (Throwable t) { return ""; }
    }
}
