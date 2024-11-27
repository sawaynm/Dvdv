package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;


public class WifiScannerFragment extends Fragment {
    public static final String TAG = "WifiScannerFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private boolean showSignalStrength = true;
    private boolean showChannel = true;
    private boolean showBSSID = true;
    private Spinner wlanInterface;
    private Spinner wifiChannel;
    private ListView wifiNetworksList;
    private final ArrayList<String> arrayList = new ArrayList<>();
    private Context context;
    private static Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private Boolean iswatch;
    private WifiManager wifiManager;

    public static WifiScannerFragment newInstance(int sectionNumber) {
        WifiScannerFragment fragment = new WifiScannerFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getContext();
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wifi_scanner, container, false);

        // Initialize WifiManager
        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Detecting watch
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);

        // WIFI Scanner
        Button scanButton = rootView.findViewById(R.id.scan_networks);
        scanButton.setOnClickListener(view -> scanWifi());

        Button showButton = rootView.findViewById(R.id.show_networks);
        showButton.setOnClickListener(view -> showShowMenu(showButton));

        Button sortButton = rootView.findViewById(R.id.sort_networks);
        sortButton.setOnClickListener(view -> showSortMenu(sortButton));

        Button clearButton = rootView.findViewById(R.id.clear_networks);
        clearButton.setOnClickListener(view -> clearList());

        wlanInterface = rootView.findViewById(R.id.wlan_interface);
        wifiChannel = rootView.findViewById(R.id.wifi_channel);
        wifiNetworksList = rootView.findViewById(R.id.wifi_networks_list);
        ArrayAdapter<String> wifiAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, arrayList);
        wifiNetworksList.setAdapter(wifiAdapter);

        // Populate Spinner with available WiFi adapters
        List<String> wifiAdapters = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.getName().startsWith("wlan")) {
                    wifiAdapters.add(networkInterface.getName());
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, wifiAdapters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wlanInterface.setAdapter(adapter);

        // Populate Spinner with available WiFi channels
        List<String> wifiChannels = new ArrayList<>();
        wifiChannels.add("All Channels");
        for (int i = 1; i <= 14; i++) {
            wifiChannels.add(String.valueOf(i));
        }

        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, wifiChannels);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiChannel.setAdapter(channelAdapter);

        return rootView;
    }

    private void clearList() {
        arrayList.clear();
        updateListView();
    }

    private void showShowMenu(View view) {
        PopupMenu popup = new PopupMenu(getContext(), view);
        popup.getMenuInflater().inflate(R.menu.show_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.show_signal_strength:
                    showSignalStrength = !showSignalStrength;
                    break;
                case R.id.show_channel:
                    showChannel = !showChannel;
                    break;
                case R.id.show_bssid:
                    showBSSID = !showBSSID;
                    break;
                default:
                    return false;
            }
            scanWifi(); // Refresh the list
            return true;
        });
        popup.show();
    }

    private void showSortMenu(View view) {
        PopupMenu popup = new PopupMenu(getContext(), view);
        popup.getMenuInflater().inflate(R.menu.sort_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.sort_by_signal:
                    sortBySignal();
                    return true;
                case R.id.sort_by_channel:
                    sortByChannel();
                    return true;
                case R.id.sort_by_name:
                    sortByName();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void sortBySignal() {
        Collections.sort(arrayList, (a, b) -> {
            int signalA = getSignalFromString(a);
            int signalB = getSignalFromString(b);
            return Integer.compare(signalB, signalA);
        });
        updateListView();
    }

    private void sortByChannel() {
        Collections.sort(arrayList, (a, b) -> {
            int channelA = getChannelFromString(a);
            int channelB = getChannelFromString(b);
            return Integer.compare(channelA, channelB);
        });
        updateListView();
    }

    private void sortByName() {
        Collections.sort(arrayList, String::compareTo);
        updateListView();
    }

    private int getSignalFromString(String str) {
        // Extract signal strength from the string
        // Assuming the format is "SSID - Channel - BSSID - Signal"
        String[] parts = str.split(" - ");
        return Integer.parseInt(parts[0].replace("%", ""));
    }

    private int getChannelFromString(String str) {
        // Extract channel from the string
        // Assuming the format is "SSID - Channel - BSSID - Signal"
        String[] parts = str.split(" - ");
        return Integer.parseInt(parts[1]);
    }

    private void updateListView() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, arrayList);
        wifiNetworksList.setAdapter(adapter);
    }

    private void scanWifi() {
        AsyncTask.execute(() -> {
            Activity activity = getActivity();
            assert activity != null;
            activity.runOnUiThread(() -> {
                // Disabling bluetooth so wifi will be definitely available for scanning
                if (iswatch) {
                    exe.RunAsRoot(new String[]{"svc bluetooth disable;settings put system clockwork_wifi_setting on"});
                } else {
                    exe.RunAsRoot(new String[]{"svc wifi enable"});
                }
                arrayList.clear();
                arrayList.add("Scanning...");
                wifiNetworksList.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, arrayList));
                wifiNetworksList.setVisibility(View.VISIBLE);
            });

            // Start WiFi scan
            wifiManager.startScan();
            if (ActivityCompat.checkSelfPermission(getContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            List<ScanResult> results = wifiManager.getScanResults();

            activity.runOnUiThread(() -> {
                if (results.isEmpty()) {
                    final ArrayList<String> noTargets = new ArrayList<>();
                    noTargets.add("No nearby WiFi networks");
                    wifiNetworksList.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_1, noTargets));
                } else {
                    ArrayList<String> scanResults = new ArrayList<>();
                    for (ScanResult result : results) {
                        StringBuilder resultString = getStringBuilder(result);
                        scanResults.add(resultString.toString());
                    }
                    arrayList.clear();
                    arrayList.addAll(scanResults);
                    sortBySignal(); // Sort by signal strength by default
                }
            });

            if (iswatch) {
                // Re-enabling bluetooth
                exe.RunAsRoot(new String[]{"svc bluetooth enable"});
            }
        });
    }

    @NonNull
    private StringBuilder getStringBuilder(ScanResult result) {
        int channel = getChannelFromFrequency(result.frequency);
        int signalPercent = getSignalStrengthInPercent(result.level);
        StringBuilder resultString = new StringBuilder();
        if (showSignalStrength) {
            resultString.append(signalPercent).append("% - ");
        }
        if (showChannel) {
            resultString.append("[").append(channel).append("] - ");
        }
        resultString.append(result.SSID);
        if (showBSSID) {
            resultString.append(" - ").append(result.BSSID);
        }
        return resultString;
    }

    private int getSignalStrengthInPercent(int level) {
        int maxLevel = -30; // Maximum signal level in dBm
        int minLevel = -100; // Minimum signal level in dBm
        return (int) ((level - minLevel) * 100.0 / (maxLevel - minLevel));
    }

    private int getChannelFromFrequency(int frequency) {
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2407) / 5;
        } else if (frequency == 2484) {
            return 14;
        } else if (frequency >= 5180 && frequency <= 5825) {
            return (frequency - 5000) / 5;
        } else {
            return -1; // Unknown frequency
        }
    }
}