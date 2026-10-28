package com.ollie.netcrawler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpnpDescriptor {

    public static String fetch(String locationUrl, int timeoutMs) {
        try {
            URL u = new URL(locationUrl);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "netcrawler/1.0");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                int cap = 0;
                while ((line = br.readLine()) != null && (cap += line.length()) < 65536)
                    sb.append(line).append('\n');
            }
            String xml = sb.toString();
            String friendly = tag(xml, "friendlyName");
            String model = tag(xml, "modelName");
            String manuf = tag(xml, "manufacturer");
            String serial = tag(xml, "serialNumber");
            return "friendly=" + friendly + " ; model=" + model
                    + " ; mfr=" + manuf + " ; sn=" + serial;
        } catch (Throwable t) { return ""; }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "\\b[^>]*>([^<]{1,160})</" + name + ">",
                Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : "-";
    }
}
