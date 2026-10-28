package com.ollie.netcrawler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class Mqtt {

    public static String probe(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 1883), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream o = s.getOutputStream();
            byte[] cid = "netcrawler".getBytes();
            byte[] body = new byte[12 + cid.length];
            int p = 0;
            body[p++] = 0; body[p++] = 4;
            body[p++] = 'M'; body[p++] = 'Q'; body[p++] = 'T'; body[p++] = 'T';
            body[p++] = 4;                     // protocol level
            body[p++] = 0x02;                  // clean session
            body[p++] = 0; body[p++] = 60;     // keepalive
            body[p++] = 0; body[p++] = (byte) cid.length;
            System.arraycopy(cid, 0, body, p, cid.length);
            o.write(new byte[]{ 0x10, (byte) body.length });
            o.write(body);
            o.flush();
            InputStream in = s.getInputStream();
            byte[] rx = new byte[4];
            int n = in.read(rx);
            if (n < 4 || (rx[0] & 0xf0) != 0x20) return "";
            int rc = rx[3] & 0xff;
            String reason;
            switch (rc) {
                case 0: reason = "accepted (anonymous OK)"; break;
                case 1: reason = "refused: proto version"; break;
                case 2: reason = "refused: client id"; break;
                case 3: reason = "refused: server unavailable"; break;
                case 4: reason = "refused: bad user/pass"; break;
                case 5: reason = "refused: not authorized"; break;
                default: reason = "rc=" + rc;
            }
            return "mqtt " + reason;
        } catch (Throwable t) { return ""; }
    }
}
