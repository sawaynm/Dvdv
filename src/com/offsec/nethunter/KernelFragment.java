package com.offsec.nethunter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.offsec.nethunter.bridge.Bridge;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.utils.ShellExecuter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Objects;

public class KernelFragment extends Fragment {
    public static final String TAG = "KernelFragment";
    private static final String ARG_SECTION_NUMBER = "section_number";
    private Activity activity;
    private final ShellExecuter exe = new ShellExecuter();

    public static KernelFragment newInstance(int sectionNumber) {
        KernelFragment fragment = new KernelFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        activity = getActivity();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.kernel, container, false);
        SharedPreferences sharedpreferences = activity.getSharedPreferences("com.offsec.nethunter", Context.MODE_PRIVATE);

        //Browse
        final Button kernelbrowseButton = rootView.findViewById(R.id.kernelfilebrowse);

        kernelbrowseButton.setOnClickListener( v -> {
            Intent intent = new Intent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select zip file"),1001);
        });

        //Flash
        Button flashButton = rootView.findViewById(R.id.flash_kernel);
        EditText kernelPath = rootView.findViewById(R.id.kernelpath);

        flashButton.setOnClickListener( v -> {
            String kernelfilepath = kernelPath.getText().toString();
            run_cmd_android(NhPaths.APP_SCRIPTS_PATH + "/bin/magic-flash " + kernelfilepath + " | awk '!/    ui_print/'");
        });
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public void onActivityResult(int requestCode, int resultCode,
                                 Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && (resultCode == Activity.RESULT_OK)) {
            ShellExecuter exe = new ShellExecuter();
            EditText kernelPath = requireActivity().findViewById(R.id.kernelpath);
            String FilePath = Objects.requireNonNull(data.getData()).getPath();
            FilePath = exe.RunAsRootOutput("echo " + FilePath + " | sed -e 's/\\/document\\/primary:/\\/sdcard\\//g' ");
            kernelPath.setText(FilePath);
        }
    }

    ////
    // Bridge side functions
    ////

    public void run_cmd(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/kali", cmd);
        activity.startActivity(intent);
    }
    public void run_cmd_android(String cmd) {
        Intent intent = Bridge.createExecuteIntent("/data/data/com.offsec.nhterm/files/usr/bin/android-su", cmd);
        activity.startActivity(intent);
    }
    public static class PreferencesData {
        public static void saveString(Context context, String key, String value) {
            SharedPreferences sharedPrefs = PreferenceManager
                    .getDefaultSharedPreferences(context);
            sharedPrefs.edit().putString(key, value).apply();
        }
        public static String getString(Context context, String key, String defaultValue) {
            SharedPreferences sharedPrefs = PreferenceManager
                    .getDefaultSharedPreferences(context);
            return sharedPrefs.getString(key, defaultValue);
        }
    }
}
