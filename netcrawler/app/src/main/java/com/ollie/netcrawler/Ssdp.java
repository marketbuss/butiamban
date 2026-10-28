package com.ollie.netcrawler;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Ssdp {

    public static List<String> discover(int totalMs, AtomicBoolean running) {
        Set<String> seen = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(400);
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: ssdp:all\r\n\r\n";
            byte[] m = msg.getBytes();
            InetAddress grp = InetAddress.getByName("239.255.255.250");
            s.send(new DatagramPacket(m, m.length, grp, 1900));

            long until = System.currentTimeMillis() + totalMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until && running.get()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (Throwable t) { continue; }
                String txt = new String(buf, 0, p.getLength(), "ISO-8859-1");
                String st = header(txt, "ST"), server = header(txt, "SERVER"), loc = header(txt, "LOCATION");
                seen.add(p.getAddress().getHostAddress()
                        + "  st=" + st + "  server=" + server + "  loc=" + loc);
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(seen);
    }

    private static String header(String txt, String k) {
        for (String line : txt.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equalsIgnoreCase(k))
                return line.substring(c + 1).trim();
        }
        return "-";
    }
}
