package com.ollie.netcrawler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpProbe {

    public static final class Result {
        public String server = "";
        public String title = "";
        public int status = 0;
        public String realm = "";
        public String location = "";
    }

    public static Result probe(String ip, int port, boolean tls, int timeoutMs) {
        Result r = new Result();
        try {
            URL u = new URL((tls ? "https" : "http") + "://" + ip + ":" + port + "/");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "netcrawler/1.0");
            r.status = c.getResponseCode();
            Map<String, List<String>> h = c.getHeaderFields();
            r.server = header(h, "Server");
            r.location = header(h, "Location");
            String www = header(h, "WWW-Authenticate");
            if (www != null) {
                Matcher m = Pattern.compile("realm=\"([^\"]+)\"").matcher(www);
                if (m.find()) r.realm = m.group(1);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    r.status < 400 ? c.getInputStream() : c.getErrorStream()))) {
                StringBuilder sb = new StringBuilder();
                String line; int cap = 0;
                while ((line = br.readLine()) != null && (cap += line.length()) < 8192) {
                    sb.append(line);
                    Matcher m = Pattern.compile("<title[^>]*>([^<]{1,120})</title>",
                            Pattern.CASE_INSENSITIVE).matcher(sb);
                    if (m.find()) { r.title = m.group(1).trim(); break; }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return r;
    }

    private static String header(Map<String, List<String>> h, String k) {
        for (Map.Entry<String, List<String>> e : h.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && !e.getValue().isEmpty())
                return e.getValue().get(0);
        return null;
    }
}
