package com.ollie.netcrawler;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public final class PortScanner {

    private static final Map<Integer, String> HINTS = new HashMap<>();
    static {
        HINTS.put(21,  "ftp");
        HINTS.put(22,  "ssh");
        HINTS.put(23,  "telnet");
        HINTS.put(25,  "smtp");
        HINTS.put(53,  "dns");
        HINTS.put(80,  "http");
        HINTS.put(110, "pop3");
        HINTS.put(139, "netbios");
        HINTS.put(143, "imap");
        HINTS.put(443, "https");
        HINTS.put(445, "smb");
        HINTS.put(554, "rtsp");
        HINTS.put(993, "imaps");
        HINTS.put(1883,"mqtt");
        HINTS.put(3389,"rdp");
        HINTS.put(5000,"upnp/http");
        HINTS.put(5555,"adb");
        HINTS.put(5900,"vnc");
        HINTS.put(7777,"oculus-wifi-dbg");
        HINTS.put(8080,"http-alt");
        HINTS.put(8443,"https-alt");
        HINTS.put(9100,"printer");
        HINTS.put(9091,"transmission");
        HINTS.put(32400,"plex");
    }

    public static boolean isOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String hint(int port) {
        String h = HINTS.get(port);
        return h == null ? "?" : h;
    }

    public static int[] parsePorts(String spec) {
        String[] parts = spec.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try { out[n++] = Integer.parseInt(p.trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (n == out.length) return out;
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }
}
