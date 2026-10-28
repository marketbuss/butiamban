package com.ollie.netcrawler;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class NetBios {

    public static String nodeStatus(String ip, int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            byte[] req = new byte[]{
                    0x12, 0x34,                // trn id
                    0x00, 0x00,                // flags
                    0x00, 0x01,                // qdcount
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x20,                      // name len (32 encoded)
                    'C','K','A','A','A','A','A','A','A','A','A','A','A','A','A','A',
                    'A','A','A','A','A','A','A','A','A','A','A','A','A','A','A','A',
                    0x00,                      // null term
                    0x00, 0x21,                // NBSTAT
                    0x00, 0x01                 // IN
            };
            s.send(new DatagramPacket(req, req.length, InetAddress.getByName(ip), 137));
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);

            int off = 57;
            if (off >= p.getLength()) return "";
            int n = buf[off] & 0xff;
            off += 1;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n && off + 18 <= p.getLength(); i++) {
                String name = new String(buf, off, 15, "ISO-8859-1").trim();
                int type = buf[off + 15] & 0xff;
                int flags = ((buf[off+16] & 0xff) << 8) | (buf[off+17] & 0xff);
                boolean group = (flags & 0x8000) != 0;
                if (sb.length() > 0) sb.append(" ; ");
                sb.append(name).append("<").append(String.format("%02x", type)).append(">")
                        .append(group ? "(G)" : "");
                off += 18;
            }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
