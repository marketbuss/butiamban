package com.ollie.netcrawler;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Mdns {

    public static List<String> discover(int totalMs, AtomicBoolean running) {
        Set<String> seen = new LinkedHashSet<>();
        try (MulticastSocket s = new MulticastSocket(5353)) {
            s.setReuseAddress(true);
            s.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("224.0.0.251");
            s.joinGroup(group);

            byte[] q = buildQuery("_services._dns-sd._udp.local");
            s.send(new DatagramPacket(q, q.length, group, 5353));
            byte[] q2 = buildQuery("_googlecast._tcp.local");
            s.send(new DatagramPacket(q2, q2.length, group, 5353));
            byte[] q3 = buildQuery("_airplay._tcp.local");
            s.send(new DatagramPacket(q3, q3.length, group, 5353));

            long until = System.currentTimeMillis() + totalMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until && running.get()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (Throwable t) { continue; }
                String names = extractNames(buf, p.getLength());
                if (!names.isEmpty())
                    seen.add(p.getAddress().getHostAddress() + "  " + names);
            }
            try { s.leaveGroup(group); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return new ArrayList<>(seen);
    }

    private static byte[] buildQuery(String name) {
        List<byte[]> labels = new ArrayList<>();
        int total = 0;
        for (String part : name.split("\\.")) {
            byte[] bs = part.getBytes();
            labels.add(bs);
            total += 1 + bs.length;
        }
        byte[] q = new byte[12 + total + 1 + 4];
        q[0] = 0; q[1] = 0;
        q[2] = 0; q[3] = 0;
        q[4] = 0; q[5] = 1;
        int pos = 12;
        for (byte[] bs : labels) { q[pos++] = (byte) bs.length; System.arraycopy(bs, 0, q, pos, bs.length); pos += bs.length; }
        q[pos++] = 0;
        q[pos++] = 0; q[pos++] = 12; // PTR
        q[pos++] = 0; q[pos] = 1;    // IN
        return q;
    }

    private static String extractNames(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        int p = 12;
        int qd = ((b[4] & 0xff) << 8) | (b[5] & 0xff);
        int an = ((b[6] & 0xff) << 8) | (b[7] & 0xff);
        try {
            for (int i = 0; i < qd && p < len; i++) {
                int[] np = {p};
                readName(b, len, np, new StringBuilder());
                p = np[0] + 4;
            }
            for (int i = 0; i < an && p < len; i++) {
                int[] np = {p};
                StringBuilder name = new StringBuilder();
                readName(b, len, np, name);
                p = np[0];
                int type = ((b[p] & 0xff) << 8) | (b[p+1] & 0xff);
                p += 8;
                int rdlen = ((b[p] & 0xff) << 8) | (b[p+1] & 0xff);
                p += 2;
                if (type == 12 || type == 33) {
                    int[] rp = {p};
                    StringBuilder tgt = new StringBuilder();
                    readName(b, len, rp, tgt);
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(name).append(" -> ").append(tgt);
                }
                p += rdlen;
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static void readName(byte[] b, int len, int[] pos, StringBuilder out) {
        int p = pos[0], hops = 0;
        boolean jumped = false;
        int finalPos = 0;
        while (p < len && hops < 20) {
            int l = b[p] & 0xff;
            if (l == 0) { p++; break; }
            if ((l & 0xc0) == 0xc0) {
                if (!jumped) { finalPos = p + 2; jumped = true; }
                p = ((l & 0x3f) << 8) | (b[p+1] & 0xff);
                hops++;
                continue;
            }
            p++;
            if (out.length() > 0) out.append('.');
            out.append(new String(b, p, l));
            p += l;
        }
        pos[0] = jumped ? finalPos : p;
    }
}
