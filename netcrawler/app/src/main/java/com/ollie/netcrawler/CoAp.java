package com.ollie.netcrawler;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoAp {

    public static List<String> discover(int totalMs, AtomicBoolean running) {
        Set<String> seen = new LinkedHashSet<>();
        try (MulticastSocket s = new MulticastSocket()) {
            s.setSoTimeout(400);
            byte[] req = new byte[]{
                    0x40, 0x01, 0x12, 0x34,
                    (byte) 0xBB, '.', 'w', 'e', 'l', 'l', '-', 'k', 'n', 'o', 'w', 'n',
                    0x04, 'c', 'o', 'r', 'e'
            };
            InetAddress grp = InetAddress.getByName("224.0.1.187");
            s.send(new DatagramPacket(req, req.length, grp, 5683));

            long until = System.currentTimeMillis() + totalMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until && running.get()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (Throwable t) { continue; }
                int body = findBody(buf, p.getLength());
                String payload = body < 0 ? "<empty>"
                        : new String(buf, body, p.getLength() - body, "ISO-8859-1");
                seen.add(p.getAddress().getHostAddress() + "  " + sanitize(payload));
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(seen);
    }

    private static int findBody(byte[] b, int len) {
        for (int i = 0; i < len; i++) if ((b[i] & 0xff) == 0xff) return i + 1;
        return -1;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString();
    }
}
