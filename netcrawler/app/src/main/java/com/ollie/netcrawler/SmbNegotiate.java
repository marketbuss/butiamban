package com.ollie.netcrawler;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class SmbNegotiate {

    public static String probe(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 445), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream o = s.getOutputStream();
            o.write(SMB2_NEGOTIATE);
            o.flush();
            InputStream in = s.getInputStream();
            byte[] hdr = new byte[4];
            if (in.read(hdr) != 4) return "";
            int len = ((hdr[1] & 0xff) << 16) | ((hdr[2] & 0xff) << 8) | (hdr[3] & 0xff);
            if (len <= 0 || len > 65535) return "";
            byte[] body = new byte[Math.min(len, 1024)];
            int off = 0;
            while (off < body.length) {
                int r = in.read(body, off, body.length - off);
                if (r <= 0) break;
                off += r;
            }
            if (off < 72) return "";
            int dialect = (body[72] & 0xff) | ((body[73] & 0xff) << 8);
            return "smb2 dialect=0x" + Integer.toHexString(dialect);
        } catch (Throwable t) {
            return smb1Probe(ip, timeoutMs);
        }
    }

    private static String smb1Probe(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 445), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.getOutputStream().write(SMB1_NEGOTIATE);
            byte[] buf = new byte[256];
            int n = s.getInputStream().read(buf);
            return n > 0 ? "smb1 responded " + n + "B" : "";
        } catch (Throwable t) { return ""; }
    }

    private static final byte[] SMB2_NEGOTIATE = hex(
        "000000c4" +                      // NetBIOS header (len=0xC4)
        "fe534d4240000100" +              // SMB2 magic + StructSize=64 + CreditCharge=1
        "00000000000000000000" +          // Status + Command=NEGOTIATE(0) + Credits
        "0000000001000000" +              // Flags + NextCommand
        "0000000000000000" +              // MessageId
        "feff000000000000" +              // Reserved + TreeId
        "0000000000000000" +              // SessionId
        "00000000000000000000000000000000" + // Signature
        "2400050001000000" +              // StructSize(36)+DialectCount(5)+Mode(1)+Reserved+Caps
        "7700000000000000000000000000000000000000" + // ClientGUID
        "0000000000000000" +              // NegotiateContextOffset
        "0202" + "1002" + "2202" + "0003" + "1103"  // dialects: 2.0.2, 2.1, 2.2.2, 3.0, 3.1.1
    );

    private static final byte[] SMB1_NEGOTIATE = hex(
        "000000a4" +
        "ff534d4272000000001843c8000000000000000000000000ffffffffffff0081" +
        "00000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000"
    );

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(i*2, i*2+2), 16);
        return b;
    }
}
