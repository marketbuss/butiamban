package com.ollie.netcrawler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Sweeper {

    public static final class Port {
        public final int port;
        public final String hint;
        Port(int p, String h) { this.port = p; this.hint = h; }
    }

    public static final class Host {
        public final String ip;
        public final long rttMs;
        public final List<Port> open = new ArrayList<>();
        Host(String ip, long rtt) { this.ip = ip; this.rttMs = rtt; }
    }

    public static final class ScanResult {
        public final List<Host> alive = new ArrayList<>();
        public int openCount;
    }

    public static ScanResult sweep(String cidr, int[] ports, AtomicBoolean running) throws Exception {
        String[] parts = cidr.split("/");
        int pfx = Integer.parseInt(parts[1]);
        if (pfx < 20) pfx = 24; // cap /24+ to keep responsive on headset
        int base = ipToInt(parts[0]);
        int mask = pfx == 32 ? -1 : (-1 << (32 - pfx));
        int net = base & mask;
        int host0 = net + 1;
        int host1 = (net | ~mask) - 1;

        ScanResult out = new ScanResult();
        ExecutorService pool = Executors.newFixedThreadPool(96);
        List<Future<Host>> futures = new ArrayList<>();
        AtomicInteger openCount = new AtomicInteger(0);

        for (int ip = host0; ip <= host1 && running.get(); ip++) {
            final int ipi = ip;
            futures.add(pool.submit(() -> probe(intToIp(ipi), ports, running, openCount)));
        }

        for (Future<Host> f : futures) {
            if (!running.get()) break;
            try {
                Host h = f.get(5, TimeUnit.SECONDS);
                if (h != null) out.alive.add(h);
            } catch (Throwable ignored) {}
        }
        pool.shutdownNow();
        out.openCount = openCount.get();
        Collections.sort(out.alive, (a, b) -> ipToInt(a.ip) - ipToInt(b.ip));
        return out;
    }

    private static Host probe(String ip, int[] ports, AtomicBoolean running, AtomicInteger openCount) {
        try {
            long t0 = System.currentTimeMillis();
            boolean up = InetAddress.getByName(ip).isReachable(600);
            long rtt = System.currentTimeMillis() - t0;

            Host h = new Host(ip, rtt);
            boolean any = up;
            for (int p : ports) {
                if (!running.get()) break;
                if (PortScanner.isOpen(ip, p, 500)) {
                    h.open.add(new Port(p, PortScanner.hint(p)));
                    openCount.incrementAndGet();
                    any = true;
                }
            }
            return any ? h : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int ipToInt(String s) {
        String[] a = s.split("\\.");
        return ((Integer.parseInt(a[0]) & 0xff) << 24)
                | ((Integer.parseInt(a[1]) & 0xff) << 16)
                | ((Integer.parseInt(a[2]) & 0xff) << 8)
                | (Integer.parseInt(a[3]) & 0xff);
    }

    private static String intToIp(int i) {
        return ((i >>> 24) & 0xff) + "." + ((i >>> 16) & 0xff) + "."
                + ((i >>> 8) & 0xff) + "." + (i & 0xff);
    }
}
