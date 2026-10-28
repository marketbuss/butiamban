package com.ollie.netcrawler;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public final class ArpTable {

    public static final class Entry {
        public final String ip, hw, dev;
        Entry(String ip, String hw, String dev) { this.ip = ip; this.hw = hw; this.dev = dev; }
    }

    public static List<Entry> read() {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] cols = line.trim().split("\\s+");
                if (cols.length < 6) continue;
                String ip = cols[0], flags = cols[2], hw = cols[3], dev = cols[5];
                if ("0x0".equals(flags) || "00:00:00:00:00:00".equals(hw)) continue;
                out.add(new Entry(ip, hw, dev));
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
