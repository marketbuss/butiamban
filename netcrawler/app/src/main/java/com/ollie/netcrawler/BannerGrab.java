package com.ollie.netcrawler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class BannerGrab {

    public static String grab(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            s.setSoTimeout(timeoutMs);

            if (port == 80 || port == 8080 || port == 5000) {
                OutputStream o = s.getOutputStream();
                String req = "HEAD / HTTP/1.0\r\nHost: " + ip + "\r\nUser-Agent: netcrawler\r\n\r\n";
                o.write(req.getBytes());
                o.flush();
            }

            InputStream in = s.getInputStream();
            byte[] buf = new byte[512];
            int n = 0, r;
            long until = System.currentTimeMillis() + timeoutMs;
            while (n < buf.length && System.currentTimeMillis() < until) {
                int avail = in.available();
                if (avail <= 0) {
                    try { Thread.sleep(60); } catch (InterruptedException ie) { break; }
                    try { r = in.read(buf, n, buf.length - n); }
                    catch (Throwable t) { break; }
                } else {
                    r = in.read(buf, n, Math.min(buf.length - n, avail));
                }
                if (r <= 0) break;
                n += r;
                if (n >= 4 && containsCRLFCRLF(buf, n)) break;
            }
            if (n <= 0) return "";
            String raw = new String(buf, 0, n, "ISO-8859-1");
            return sanitize(firstLine(raw));
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean containsCRLFCRLF(byte[] b, int n) {
        for (int i = 3; i < n; i++)
            if (b[i-3]=='\r' && b[i-2]=='\n' && b[i-1]=='\r' && b[i]=='\n') return true;
        return false;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        if (i < 0) return s;
        String out = s.substring(0, i);
        if (out.endsWith("\r")) out = out.substring(0, out.length()-1);
        return out;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length() && sb.length() < 160; i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString().trim();
    }
}
