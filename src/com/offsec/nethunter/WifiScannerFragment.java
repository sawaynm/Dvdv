package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.PopupMenu;
import android.Manifest;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.offsec.nethunter.utils.ShellExecuter;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Collections;
import java.util.Objects;


public class WifiScannerFragment extends Fragment {
    public static final String TAG = "WifiScannerFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private boolean showSignalStrength = true;
    private boolean showChannel = true;
    private boolean showBSSID = false;
    private ListView wifiNetworksList;
    private final ArrayList<String> arrayList = new ArrayList<>();
    private Context context;
    private Activity activity;
    private final ShellExecuter exe = new ShellExecuter();
    private Boolean iswatch;
    private WifiManager wifiManager;
    private final Handler handler = new Handler();
    private Runnable scanRunnable;
    private String selectedSSID = null;
    private SwipeRefreshLayout swipeRefreshLayout;
    private int refreshInterval = 10000; // Default to 10 seconds

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
        //Snackbar.make(requireView(), "Preparing WiFi Scanner and utilities ..", Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.wifi_scanner, container, false);
        //Snackbar.make(requireView(), "Preparing WiFi Scanner and utilities ..", Snackbar.LENGTH_SHORT).show();

        // Initialize WifiManager
        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Initialize SwipeRefreshLayout
        swipeRefreshLayout = rootView.findViewById(R.id.swipe_refresh_layout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            scanWifi();
            swipeRefreshLayout.setRefreshing(false);
        });

        // Initialize Bottom Navigation View
        BottomNavigationView bottomNavigationView = rootView.findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnNavigationItemSelectedListener(item -> {
            switch (item.getItemId()) {
                case R.id.navigation_home:
                    // Handle home action
                    return true;
                case R.id.navigation_dashboard:
                    // Handle dashboard action
                    return true;
                case R.id.navigation_notifications:
                    // Handle notifications action
                    return true;
            }
            return false;
        });

        // Detecting watch
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);
        iswatch = sharedpreferences.getBoolean("running_on_wearos", false);

        // WIFI Scanner
        Button showButton = rootView.findViewById(R.id.show_networks);
        showButton.setOnClickListener(view -> showShowMenu(showButton));

        Button sortButton = rootView.findViewById(R.id.sort_networks);
        sortButton.setOnClickListener(view -> showSortMenu(sortButton));

        Button clearButton = rootView.findViewById(R.id.clear_networks);
        clearButton.setOnClickListener(view -> clearList());

        Spinner wlanInterface = rootView.findViewById(R.id.wlan_interface);
        Spinner wifiChannel = rootView.findViewById(R.id.wifi_channel);
        wifiNetworksList = rootView.findViewById(R.id.wifi_networks_list);
        ArrayAdapter<String> wifiAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, arrayList);
        wifiNetworksList.setAdapter(wifiAdapter);

        Spinner refreshIntervalSpinner = rootView.findViewById(R.id.refresh_interval);
        List<String> refreshOptions = Arrays.asList("OFF", "5 seconds", "8 seconds", "10 seconds", "15 seconds", "20 seconds");
        ArrayAdapter<String> refreshAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, refreshOptions);
        refreshAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refreshIntervalSpinner.setAdapter(refreshAdapter);
        refreshIntervalSpinner.setSelection(3); // Set default to '10 seconds'

        refreshIntervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        refreshInterval = 0;
                        break;
                    case 1:
                        refreshInterval = 5000;
                        break;
                    case 2:
                        refreshInterval = 8000;
                        break;
                    case 3:
                        refreshInterval = 10000;
                        break;
                    case 4:
                        refreshInterval = 15000;
                        break;
                    case 5:
                        refreshInterval = 20000;
                        break;
                }
                scheduleScan();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

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

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, wifiAdapters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wlanInterface.setAdapter(adapter);

        // Populate Spinner with available WiFi channels
        List<String> wifiChannels = new ArrayList<>();
        wifiChannels.add("All Channels");
        for (int i = 1; i <= 14; i++) {
            wifiChannels.add(String.valueOf(i));
        }

        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, wifiChannels);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wifiChannel.setAdapter(channelAdapter);

        // Set OnItemClickListener to hold selection
        wifiNetworksList.setOnItemClickListener((parent, view, position, id) -> {
            for (int i = 0; i < parent.getChildCount(); i++) {
                parent.getChildAt(i).setBackgroundColor(Color.TRANSPARENT); // Reset background color for all items
            }
            view.setBackgroundColor(Color.LTGRAY); // Set background color for selected item
        });

        return rootView;
    }

    private void clearList() {
        arrayList.clear();
        updateListView();
        Snackbar.make(requireView(), "Terminal was cleared", Snackbar.LENGTH_SHORT).show();
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
        // Assuming the format is "SSID - Channel - BSSID - Signal - ENC"
        String[] parts = str.split(" - ");
        return Integer.parseInt(parts[0].replace("%", ""));
    }

    private int getChannelFromString(String str) {
        // Extract channel from the string
        // Assuming the format is "SSID - Channel - BSSID - Signal - ENC"
        String[] parts = str.split(" - ");
        return Integer.parseInt(parts[1]);
    }

    private void updateListView() {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), R.layout.wifi_network_item, arrayList) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext()).inflate(R.layout.wifi_network_item, parent, false);
                }

                String[] parts = Objects.requireNonNull(getItem(position)).split(" - ");
                TextView signal = convertView.findViewById(R.id.signal);
                TextView channel = convertView.findViewById(R.id.channel);
                TextView bssid = convertView.findViewById(R.id.bssid);
                TextView encryption = convertView.findViewById(R.id.encryption);

                if (parts.length > 0) {
                    int signalPercent = Integer.parseInt(parts[0].replace("%", ""));
                    signal.setText(parts[0]);

                    if (signalPercent >= 1 && signalPercent <= 20) {
                        signal.setTextColor(Color.RED);
                    } else if (signalPercent >= 21 && signalPercent <= 40) {
                        signal.setTextColor(Color.YELLOW);
                    } else if (signalPercent > 40) {
                        signal.setTextColor(Color.GREEN);
                    }
                } else {
                    signal.setText("");
                }

                channel.setText(parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "");
                bssid.setText(parts.length > 2 && !parts[2].isEmpty() ? parts[2] : "");
                encryption.setText(parts.length > 3 && !parts[3].isEmpty() ? parts[3] : "");

                if (selectedSSID != null && selectedSSID.equals(parts[2])) {
                    convertView.setBackgroundColor(Color.DKGRAY); // Set background color for selected item
                    signal.setTextColor(Color.RED); // Set text color to red for selected item
                    channel.setTextColor(Color.RED);
                    bssid.setTextColor(Color.RED);
                    encryption.setTextColor(Color.RED);
                } else {
                    convertView.setBackgroundColor(Color.TRANSPARENT); // Reset background color for non-selected items
                    //signal.setTextColor(Color.WHITE); // Reset text color for non-selected items
                    //channel.setTextColor(Color.WHITE);
                    //bssid.setTextColor(Color.WHITE);
                    encryption.setTextColor(Color.parseColor("#FFA500")); // Set text color to orange for non-selected items
                }

                return convertView;
            }
        };
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
                wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, arrayList));
                wifiNetworksList.setVisibility(View.VISIBLE);
            });

            // Start WiFi scan
            wifiManager.startScan();
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                return;
            }
            List<ScanResult> results = wifiManager.getScanResults();

            activity.runOnUiThread(() -> {
                if (results.isEmpty()) {
                    final ArrayList<String> noTargets = new ArrayList<>();
                    noTargets.add("No nearby WiFi networks");
                    wifiNetworksList.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, noTargets));
                    Snackbar.make(requireView(), "No nearby WiFi networks", Snackbar.LENGTH_SHORT).show();
                } else {
                    ArrayList<String> scanResults = new ArrayList<>();
                    for (ScanResult result : results) {
                        StringBuilder resultString = getStringBuilder(result);
                        scanResults.add(resultString.toString());
                    }
                    arrayList.clear();
                    arrayList.addAll(scanResults);
                    sortBySignal(); // Sort by signal strength by default
                    //Snackbar.make(requireView(), "Scan complete", Snackbar.LENGTH_SHORT).show();
                }
            });

            if (iswatch) {
                // Re-enabling bluetooth
                exe.RunAsRoot(new String[]{"svc bluetooth enable"});
            }
        });
    }

    private void scheduleScan() {
        if (scanRunnable != null) {
            handler.removeCallbacks(scanRunnable);
        }
        if (refreshInterval > 0) {
            scanRunnable = new Runnable() {
                @Override
                public void run() {
                    scanWifi();
                    handler.postDelayed(this, refreshInterval);
                }
            };
            handler.post(scanRunnable);
        }
    }

    @NonNull
    private StringBuilder getStringBuilder(ScanResult result) {
        int channel = getChannelFromFrequency(result.frequency);
        int signalPercent = getSignalStrengthInPercent(result.level);
        String encryptionType = getEncryptionType(result);
        StringBuilder resultString = new StringBuilder();
        if (showSignalStrength) {
            resultString.append(signalPercent).append("% - ");
        }
        if (showChannel) {
            resultString.append(channel).append(" - ");
        }
        resultString.append(result.SSID);
        if (showBSSID) {
            resultString.append(" - ").append(result.BSSID);
        }
        resultString.append(" - ").append(encryptionType);
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

    private String getEncryptionType(ScanResult result) {
        String capabilities = result.capabilities;
        String encryptionType = "Open";

        if (capabilities.contains("WPA3")) {
            encryptionType = "WPA3";
        } else if (capabilities.contains("WPA2")) {
            encryptionType = "WPA2";
        } else if (capabilities.contains("WPA")) {
            encryptionType = "WPA";
        } else if (capabilities.contains("WEP")) {
            encryptionType = "WEP";
        }

        if (capabilities.contains("WPS")) {
            encryptionType += "+WPS";
        }

        return encryptionType;
    }
}
