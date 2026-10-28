package com.ollie.netcrawler;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private TextView log;
    private TextView status;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread worker;

    private CheckBox cbBanner, cbUpnp, cbMdns, cbSsdp, cbCoap,
                     cbNetbios, cbSnmp, cbSmb, cbMqtt, cbHttp, cbWifi, cbReport;
    private EditText etCidr, etPorts;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        log = findViewById(R.id.log);
        status = findViewById(R.id.status);
        log.setMovementMethod(new ScrollingMovementMethod());

        etCidr = findViewById(R.id.cidr);
        etPorts = findViewById(R.id.ports);

        cbBanner  = findViewById(R.id.cbBanner);
        cbUpnp    = findViewById(R.id.cbUpnp);
        cbMdns    = findViewById(R.id.cbMdns);
        cbSsdp    = findViewById(R.id.cbSsdp);
        cbCoap    = findViewById(R.id.cbCoap);
        cbNetbios = findViewById(R.id.cbNetbios);
        cbSnmp    = findViewById(R.id.cbSnmp);
        cbSmb     = findViewById(R.id.cbSmb);
        cbMqtt    = findViewById(R.id.cbMqtt);
        cbHttp    = findViewById(R.id.cbHttp);
        cbWifi    = findViewById(R.id.cbWifi);
        cbReport  = findViewById(R.id.cbReport);

        ((Button) findViewById(R.id.btnScan)).setOnClickListener(v -> startScan());
        ((Button) findViewById(R.id.btnStop)).setOnClickListener(v -> stopScan());
        ((Button) findViewById(R.id.btnClear)).setOnClickListener(v -> log.setText(""));

        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
        }, 1);

        println("╔═══ netcrawler ═══ quest 3 lan recon ═══╗");
        println("║ built by olivia — discord.gg/ojailbreaks");
        println("╚════════════════════════════════════════╝");
        println("[hint] blank cidr = auto /24 from wifi iface");
        println("[hint] blank ports = 22,80,443,445,554,1883,5000,5555,8080,8443,9100");
    }

    private void startScan() {
        if (running.get()) { println("[busy] already scanning"); return; }
        String cidr = etCidr.getText().toString().trim();
        if (cidr.isEmpty()) cidr = NetInfo.autoCidr(this);
        if (cidr == null) { println("[err] not on wifi"); return; }
        String portsStr = etPorts.getText().toString().trim();
        if (portsStr.isEmpty()) portsStr = "22,80,443,445,554,1883,5000,5555,8080,8443,9100";
        final int[] portList = PortScanner.parsePorts(portsStr);
        final String finalCidr = cidr;

        final boolean oBanner  = cbBanner.isChecked();
        final boolean oUpnp    = cbUpnp.isChecked();
        final boolean oMdns    = cbMdns.isChecked();
        final boolean oSsdp    = cbSsdp.isChecked();
        final boolean oCoap    = cbCoap.isChecked();
        final boolean oNetbios = cbNetbios.isChecked();
        final boolean oSnmp    = cbSnmp.isChecked();
        final boolean oSmb     = cbSmb.isChecked();
        final boolean oMqtt    = cbMqtt.isChecked();
        final boolean oHttp    = cbHttp.isChecked();
        final boolean oWifi    = cbWifi.isChecked();
        final boolean oReport  = cbReport.isChecked();

        running.set(true);
        setStatus("scanning " + finalCidr);

        worker = new Thread(() -> {
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"ts\": ").append(System.currentTimeMillis());
            json.append(",\n  \"cidr\": ").append(ReportJson.esc(finalCidr));
            try {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                WifiManager.MulticastLock lock = wm.createMulticastLock("netcrawler");
                lock.setReferenceCounted(false);
                lock.acquire();
                try {
                    println("[*] wifi: " + NetInfo.summary(MainActivity.this));
                    json.append(",\n  \"wifi\": ").append(ReportJson.esc(NetInfo.summary(this)));

                    if (oWifi) {
                        println("[*] wifi scan (nearby APs):");
                        wm.startScan();
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        List<String> aps = WifiScan.results(this);
                        json.append(",\n  \"wifi_scan\": [");
                        for (int i = 0; i < aps.size(); i++) {
                            println("    " + aps.get(i));
                            json.append(i == 0 ? "" : ",")
                                    .append("\n    ").append(ReportJson.esc(aps.get(i)));
                        }
                        json.append("\n  ]");
                    }

                    println("[*] arp cache:");
                    json.append(",\n  \"arp\": [");
                    int n = 0;
                    for (ArpTable.Entry e : ArpTable.read()) {
                        println("    " + e.ip + "  " + e.hw + "  dev=" + e.dev);
                        json.append(n++ == 0 ? "" : ",")
                                .append("\n    {\"ip\":").append(ReportJson.esc(e.ip))
                                .append(", \"mac\":").append(ReportJson.esc(e.hw))
                                .append(", \"dev\":").append(ReportJson.esc(e.dev)).append("}");
                    }
                    json.append("\n  ]");

                    Sweeper.ScanResult res = Sweeper.sweep(finalCidr, portList, running);
                    println("[*] alive hosts: " + res.alive.size()
                            + "  open sockets: " + res.openCount);
                    json.append(",\n  \"hosts\": [");

                    int hostIdx = 0;
                    for (Sweeper.Host h : res.alive) {
                        if (!running.get()) break;
                        println("--- " + h.ip + "  rtt " + h.rttMs + " ms");
                        String ptr = OsFingerprint.reverse(h.ip);
                        if (!ptr.isEmpty()) println("    ptr: " + ptr);

                        String nb = oNetbios ? NetBios.nodeStatus(h.ip, 1200) : "";
                        if (!nb.isEmpty()) println("    netbios: " + nb);

                        String snmp = oSnmp ? Snmp.sysDescr(h.ip, "public", 1200) : "";
                        if (!snmp.isEmpty()) println("    snmp(public): " + snmp);

                        json.append(hostIdx++ == 0 ? "" : ",")
                                .append("\n    {\"ip\":").append(ReportJson.esc(h.ip))
                                .append(", \"rtt_ms\":").append(h.rttMs)
                                .append(", \"ptr\":").append(ReportJson.esc(ptr))
                                .append(", \"netbios\":").append(ReportJson.esc(nb))
                                .append(", \"snmp\":").append(ReportJson.esc(snmp))
                                .append(", \"ports\": [");

                        int pi = 0;
                        for (Sweeper.Port p : h.open) {
                            if (!running.get()) break;
                            String banner = oBanner ? BannerGrab.grab(h.ip, p.port, 1200) : "";
                            String smb = (oSmb && p.port == 445) ? SmbNegotiate.probe(h.ip, 1500) : "";
                            String mqtt = (oMqtt && p.port == 1883) ? Mqtt.probe(h.ip, 1500) : "";
                            HttpProbe.Result hr = null;
                            if (oHttp && (p.port == 80 || p.port == 443 || p.port == 8080
                                    || p.port == 8443 || p.port == 5000 || p.port == 7777)) {
                                hr = HttpProbe.probe(h.ip, p.port, p.port == 443 || p.port == 8443, 1500);
                            }

                            StringBuilder line = new StringBuilder();
                            line.append("    ").append(p.port).append("/tcp open  ").append(p.hint);
                            if (!banner.isEmpty()) line.append("  :: ").append(banner);
                            if (!smb.isEmpty()) line.append("  :: ").append(smb);
                            if (!mqtt.isEmpty()) line.append("  :: ").append(mqtt);
                            if (hr != null) {
                                line.append("  :: http ").append(hr.status);
                                if (!hr.server.isEmpty()) line.append(" srv=").append(hr.server);
                                if (!hr.title.isEmpty()) line.append(" title=").append(hr.title);
                                if (!hr.realm.isEmpty()) line.append(" realm=").append(hr.realm);
                                if (!hr.location.isEmpty()) line.append(" loc=").append(hr.location);
                            }
                            println(line.toString());

                            json.append(pi++ == 0 ? "" : ",")
                                    .append("\n      {\"port\":").append(p.port)
                                    .append(", \"hint\":").append(ReportJson.esc(p.hint))
                                    .append(", \"banner\":").append(ReportJson.esc(banner))
                                    .append(", \"smb\":").append(ReportJson.esc(smb))
                                    .append(", \"mqtt\":").append(ReportJson.esc(mqtt))
                                    .append(", \"http\":").append(hr == null ? "null"
                                            : "{\"status\":" + hr.status
                                            + ", \"server\":" + ReportJson.esc(hr.server)
                                            + ", \"title\":" + ReportJson.esc(hr.title)
                                            + ", \"realm\":" + ReportJson.esc(hr.realm) + "}")
                                    .append("}");
                        }
                        json.append("\n    ]}");
                    }
                    json.append("\n  ]");

                    if (oMdns) {
                        println("[*] mdns:");
                        List<String> mdns = Mdns.discover(2500, running);
                        json.append(",\n  \"mdns\": [");
                        for (int i = 0; i < mdns.size(); i++) {
                            println("    " + mdns.get(i));
                            json.append(i == 0 ? "" : ",").append("\n    ").append(ReportJson.esc(mdns.get(i)));
                        }
                        json.append("\n  ]");
                    }

                    if (oSsdp) {
                        println("[*] ssdp:");
                        List<String> ssdp = Ssdp.discover(2500, running);
                        List<String> upnpInfo = new ArrayList<>();
                        for (String row : ssdp) {
                            println("    " + row);
                            if (oUpnp) {
                                int li = row.indexOf("loc=");
                                if (li > 0) {
                                    String loc = row.substring(li + 4).trim();
                                    if (loc.startsWith("http")) {
                                        String info = UpnpDescriptor.fetch(loc, 1500);
                                        if (!info.isEmpty()) {
                                            println("      → upnp: " + info);
                                            upnpInfo.add(loc + " :: " + info);
                                        }
                                    }
                                }
                            }
                        }
                        json.append(",\n  \"ssdp\": [");
                        for (int i = 0; i < ssdp.size(); i++) {
                            json.append(i == 0 ? "" : ",").append("\n    ").append(ReportJson.esc(ssdp.get(i)));
                        }
                        json.append("\n  ]");
                        json.append(",\n  \"upnp\": [");
                        for (int i = 0; i < upnpInfo.size(); i++) {
                            json.append(i == 0 ? "" : ",").append("\n    ").append(ReportJson.esc(upnpInfo.get(i)));
                        }
                        json.append("\n  ]");
                    }

                    if (oCoap) {
                        println("[*] coap:");
                        List<String> coap = CoAp.discover(2000, running);
                        json.append(",\n  \"coap\": [");
                        for (int i = 0; i < coap.size(); i++) {
                            println("    " + coap.get(i));
                            json.append(i == 0 ? "" : ",").append("\n    ").append(ReportJson.esc(coap.get(i)));
                        }
                        json.append("\n  ]");
                    }

                } finally {
                    try { lock.release(); } catch (Throwable ignored) {}
                }
                json.append("\n}\n");

                if (oReport) {
                    String path = ReportJson.write(this, json.toString());
                    println("[report] " + path);
                }
                println("[done]");
            } catch (Throwable t) {
                println("[err] " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                running.set(false);
                setStatus("idle");
            }
        }, "netcrawler-worker");
        worker.start();
    }

    private void stopScan() {
        if (!running.get()) return;
        running.set(false);
        setStatus("stopping…");
        println("[!] stop requested");
    }

    private void println(final String s) {
        ui.post(() -> {
            log.append(s + "\n");
            View parent = (View) log.getParent();
            if (parent instanceof ScrollView)
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void setStatus(final String s) {
        ui.post(() -> status.setText(s));
    }

    @Override
    protected void onDestroy() {
        running.set(false);
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
