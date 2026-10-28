package com.ollie.netcrawler;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class Snmp {

    public static String sysDescr(String ip, String community, int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            byte[] req = buildGet(community, new int[]{1,3,6,1,2,1,1,1,0});
            s.send(new DatagramPacket(req, req.length, InetAddress.getByName(ip), 161));
            byte[] buf = new byte[1500];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return parseOctetString(buf, p.getLength());
        } catch (Throwable t) { return ""; }
    }

    private static byte[] buildGet(String community, int[] oid) {
        byte[] oidEnc = encodeOid(oid);
        byte[] var = tlv(0x30,
                concat(tlv(0x06, oidEnc), tlv(0x05, new byte[0])));
        byte[] varList = tlv(0x30, var);
        byte[] pdu = tlv(0xA0,
                concat(tlv(0x02, new byte[]{0x26, 0x5F, (byte)0xDE, (byte)0xAD}),
                       tlv(0x02, new byte[]{0}),
                       tlv(0x02, new byte[]{0}),
                       varList));
        return tlv(0x30,
                concat(tlv(0x02, new byte[]{0}),
                       tlv(0x04, community.getBytes()),
                       pdu));
    }

    private static byte[] encodeOid(int[] parts) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            if (v < 128) { b.write(v); continue; }
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(v & 0x7f);
            v >>>= 7;
            while (v > 0) { stack.push(0x80 | (v & 0x7f)); v >>>= 7; }
            while (!stack.isEmpty()) b.write(stack.pop());
        }
        return b.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] body) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(tag);
        int L = body.length;
        if (L < 128) b.write(L);
        else {
            java.util.ArrayDeque<Integer> st = new java.util.ArrayDeque<>();
            int v = L;
            while (v > 0) { st.push(v & 0xff); v >>>= 8; }
            b.write(0x80 | st.size());
            while (!st.isEmpty()) b.write(st.pop());
        }
        b.write(body, 0, body.length);
        return b.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int tot = 0;
        for (byte[] p : parts) tot += p.length;
        byte[] out = new byte[tot];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    private static String parseOctetString(byte[] b, int len) {
        for (int i = 0; i < len - 2; i++) {
            if ((b[i] & 0xff) == 0x04) {
                int L = b[i+1] & 0xff;
                int off = i + 2;
                if ((L & 0x80) != 0) {
                    int n = L & 0x7f;
                    L = 0;
                    for (int k = 0; k < n; k++) L = (L << 8) | (b[i + 2 + k] & 0xff);
                    off = i + 2 + n;
                }
                if (off + L > len || L < 4) continue;
                String s = new String(b, off, L, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (s.length() > 10 && s.chars().allMatch(c -> c >= 32 && c < 127)) return s;
            }
        }
        return "";
    }
}
