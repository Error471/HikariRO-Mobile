package com.winlator.xenvironment.components;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;

import com.winlator.core.FileUtils;
import com.winlator.core.NetworkHelper;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private BroadcastReceiver broadcastReceiver;

    @Override
    public void start() {
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);
        updateIFAddrsFile(networkHelper.getIFAddresses());
        updateEtcHostsFile(networkHelper.getIPv4Address());
        updateResolvConfFile(context);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateIFAddrsFile(networkHelper.getIFAddresses());
                updateEtcHostsFile(networkHelper.getIPv4Address());
                updateResolvConfFile(context);
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(broadcastReceiver, filter);
    }

    @Override
    public void stop() {
        if (broadcastReceiver != null) {
            environment.getContext().unregisterReceiver(broadcastReceiver);
            broadcastReceiver = null;
        }
    }

    private void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getRootFS().getTmpDir(), "ifaddrs");

        String content = "";
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                content += (!content.isEmpty() ? "\n" : "")+ifAddress.toString();
            }
        }
        else content = (new NetworkHelper.IFAddress()).toString();

        FileUtils.writeString(file, content);

        // nsiproxy.so (parcheado con el prefijo corto, ver RootFSInstaller) lee
        // "/data/data/<paquete>/tmp/ifaddrs", no la copia de dentro del rootfs.
        // Sin este fichero iphlpapi.dll recibia datos basura y el patcher
        // petaba (Access violation en iphlpapi.dll al pasar por las noticias).
        File shortTmpDir = new File(environment.getContext().getDataDir(), "tmp");
        if (!shortTmpDir.isDirectory()) shortTmpDir.mkdirs();
        FileUtils.writeString(new File(shortTmpDir, "ifaddrs"), content);
    }

    // DNS para glibc (wine resuelve nombres con getaddrinfo de glibc): los
    // servidores de la red actual del movil y, de respaldo, DNS publicos.
    private void updateResolvConfFile(Context context) {
        java.util.LinkedHashSet<String> servers = new java.util.LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network network = cm != null ? cm.getActiveNetwork() : null;
            android.net.LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
            if (props != null) {
                for (java.net.InetAddress address : props.getDnsServers()) {
                    if (address instanceof java.net.Inet4Address) servers.add(address.getHostAddress());
                }
            }
        }
        catch (Exception e) {}
        servers.add("1.1.1.1");
        servers.add("8.8.8.8");

        StringBuilder content = new StringBuilder();
        int count = 0;
        for (String server : servers) {
            if (count++ >= 3) break; // glibc solo usa 3
            content.append("nameserver ").append(server).append("\n");
        }
        content.append("options timeout:2 attempts:2\n");
        FileUtils.writeString(new File(environment.getRootFS().getRootDir(), "etc/resolv.conf"), content.toString());
    }

    private void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getRootFS().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip+"\tlocalhost\n");
    }
}
