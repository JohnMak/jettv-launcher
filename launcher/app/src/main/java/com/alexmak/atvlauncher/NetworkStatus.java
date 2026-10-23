package com.alexmak.atvlauncher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;

/** Event-driven VPN and network-link state; callbacks are registered only between register() and unregister(). */
final class NetworkStatus {
    interface Listener {
        void onVpnChanged(boolean on);

        void onLinkChanged(LinkState link);
    }

    private final ConnectivityManager cm;
    private final WifiManager wifiManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Set<Network> vpnNetworks = new HashSet<>();
    private final Set<Network> ethernetNetworks = new HashSet<>();
    private boolean wifiUp;
    private int wifiLevel;
    private String ssid;
    private boolean registered;

    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            vpnNetworks.add(network);
            listener.onVpnChanged(true);
        }

        @Override
        public void onLost(Network network) {
            vpnNetworks.remove(network);
            listener.onVpnChanged(!vpnNetworks.isEmpty());
        }
    };

    private final ConnectivityManager.NetworkCallback wifiCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    wifiUp = true;
                    wifiLevel = 0;
                    ssid = null;
                    // WifiManager.getConnectionInfo works on API 30..34 (needs ACCESS_FINE_LOCATION for the SSID);
                    // NetworkCapabilities.getTransportInfo only carries location data since API 31 with a callback flag.
                    @SuppressWarnings("deprecation")
                    WifiInfo info = wifiManager.getConnectionInfo();
                    if (info != null) {
                        wifiLevel = wifiManager.calculateSignalLevel(info.getRssi());
                        String raw = info.getSSID();
                        if (raw != null && !raw.contains("unknown ssid")) {
                            ssid = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
                                    ? raw.substring(1, raw.length() - 1) : raw;
                        }
                    }
                    publishLink();
                }

                @Override
                public void onLost(Network network) {
                    wifiUp = false;
                    publishLink();
                }
            };

    private final ConnectivityManager.NetworkCallback ethernetCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            ethernetNetworks.add(network);
            publishLink();
        }

        @Override
        public void onLost(Network network) {
            ethernetNetworks.remove(network);
            publishLink();
        }
    };

    NetworkStatus(Context context, Listener listener) {
        this.cm = context.getSystemService(ConnectivityManager.class);
        this.wifiManager = context.getSystemService(WifiManager.class);
        this.listener = listener;
    }

    void register() {
        if (registered) return;
        registered = true;
        cm.registerNetworkCallback(transport(NetworkCapabilities.TRANSPORT_VPN, true), vpnCallback, main);
        cm.registerNetworkCallback(transport(NetworkCapabilities.TRANSPORT_WIFI, false), wifiCallback, main);
        cm.registerNetworkCallback(transport(NetworkCapabilities.TRANSPORT_ETHERNET, false), ethernetCallback, main);
        // No callback fires when a network went away while unregistered, so seed the states.
        NetworkCapabilities active = cm.getNetworkCapabilities(cm.getActiveNetwork());
        listener.onVpnChanged(active != null && active.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        publishLink();
    }

    void unregister() {
        if (!registered) return;
        registered = false;
        cm.unregisterNetworkCallback(vpnCallback);
        cm.unregisterNetworkCallback(wifiCallback);
        cm.unregisterNetworkCallback(ethernetCallback);
        vpnNetworks.clear();
        ethernetNetworks.clear();
        wifiUp = false;
    }

    private static NetworkRequest transport(int transport, boolean vpn) {
        NetworkRequest.Builder b = new NetworkRequest.Builder().addTransportType(transport);
        if (vpn) b.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        return b.build();
    }

    private void publishLink() {
        listener.onLinkChanged(LinkState.resolve(!ethernetNetworks.isEmpty(), wifiUp, wifiLevel, ssid));
    }
}
