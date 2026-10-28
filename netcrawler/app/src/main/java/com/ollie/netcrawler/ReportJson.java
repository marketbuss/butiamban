package com.ollie.netcrawler;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ReportJson {

    public static String write(Context ctx, String payload) {
        String name = "netcrawler-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".json";
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = Environment.getExternalStorageDirectory();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "write failed: " + t.getMessage();
        }
    }

    public static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
