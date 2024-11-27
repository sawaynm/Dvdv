package com.offsec.nethunter;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.RemoteViews;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import android.util.Log;

import static com.offsec.nethunter.AudioPlayState.BUFFERING;
import static com.offsec.nethunter.AudioPlayState.STARTING;

public class AudioPlaybackService extends Service implements AudioPlaybackWorker.Listener {
    /**
     * Unique ID for the Notification.
     */
    private static final int NOTIFICATION = R.string.playback_service_status;

    private static final String ACTION_TOGGLE = AudioPlaybackService.class.getName() + ".TOGGLE";

    public static final String KEY_BUFFER_HEADROOM = "buffer_ms_ahead";
    public static final String KEY_TARGET_LATENCY = "buffer_ms_behind";

    private final IBinder binder = new LocalBinder(this);

    private Handler handler = new Handler();
    private NotificationManager notifManager;
    private PowerManager.WakeLock wakeLock;
    private PendingIntent togglePendingIntent;

    @Nullable
    private AudioPlaybackWorker playWorker = null;
    @Nullable
    private Thread playWorkerThread;
    private long headroomUsec = 125000;
    private long latencyUsec = 1000000;

    private final MutableLiveData<AudioPlayState> playState = new MutableLiveData<>();
    private SharedPreferences sharedPrefs;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        handler = new Handler();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        sharedPrefs = getSharedPreferences(AudioPlaybackService.class.getName(), Context.MODE_PRIVATE);

        headroomUsec = getBufferSizePref(KEY_BUFFER_HEADROOM, 125000);
        latencyUsec = getBufferSizePref(KEY_TARGET_LATENCY, 500000);

        playState.setValue(AudioPlayState.STOPPED);

        Intent intent = new Intent(this, AudioPlaybackService.class)
                .setAction(ACTION_TOGGLE);
        togglePendingIntent = PendingIntent.getService(
                this, R.id.intent_toggle_service, intent, PendingIntent.FLAG_IMMUTABLE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        assert pm != null;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audio:wakelock");

        if (Build.VERSION.SDK_INT >= 26) {
            notifManager.createNotificationChannel(new NotificationChannel(
                    getString(R.string.service_notification_channel),
                    getString(R.string.playback_service_label),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_TOGGLE.equals(intent.getAction())) {
                if (getPlayState() == AudioPlayState.STOPPED) {
                    play(getServerPref(), getPortPref());
                } else {
                    stop();
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Stop playback and nullify worker references
        stop();
        stopWorker();

        // Release the WakeLock if it is held
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.d("AudioFragment", "WakeLock released.");
        }
        wakeLock = null;

        // Remove all callbacks and messages from the Handler to avoid memory leaks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
            Log.d("AudioFragment", "Handler callbacks removed.");
        }
        handler = null;

        // Cancel notifications to release resources in the NotificationManager
        if (notifManager != null) {
            notifManager.cancel(NOTIFICATION);
            notifManager = null;
        }

        togglePendingIntent = null;

        // Ensure that the play state LiveData is cleared to release observers
        playState.setValue(AudioPlayState.STOPPED);

        Log.d("AudioFragment", "AudioPlaybackService destroyed and cleaned up.");
    }

    @SuppressLint("InlinedApi")
    @Override
    public void onPlaybackError(@NonNull AudioPlaybackWorker worker, @NonNull Throwable t) {
        if (worker == playWorker) {
            notifyState(AudioPlayState.STOPPED);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    @Override
    public void onPlaybackBuffering(@NonNull AudioPlaybackWorker worker) {
        if (worker == playWorker) {
            notifyState(BUFFERING);
        }
    }

    @Override
    public void onPlaybackStarted(@NonNull AudioPlaybackWorker worker) {
        if (worker == playWorker) {
            notifyState(AudioPlayState.STARTED);
        }
    }

    @Override
    public void onPlaybackStopped(@NonNull AudioPlaybackWorker worker) {
        if (worker == playWorker) {
            notifyState(AudioPlayState.STOPPED);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
            stopSelf();
        }
    }

    @MainThread
    public void play(@NonNull String server, int port) {
        if (!isStartable()) {
            throw new IllegalStateException("Cannot start with playState == " + getPlayState());
        }
        if (playWorker != null) {
            stopWorker();
        }
        playWorker = new AudioPlaybackWorker(server, port, wakeLock, handler, this);
        playWorker.setBufferUsec(headroomUsec, latencyUsec);
        playWorkerThread = new Thread(playWorker);

        startForeground(NOTIFICATION, createNotification(STARTING));

        notifyState(STARTING);

        playWorkerThread.start();

        // allow running in the background when service gets unbound
        startService(new Intent(this, AudioPlaybackService.class));
    }

    @MainThread
    public void stop() {
        if (getPlayState().isActive()) {
            notifyState(AudioPlayState.STOPPING);
        }
        stopWorker();
        notifyState(AudioPlayState.STOPPED);
    }

    @MainThread
    private void stopWorker() {
        // Nullify references to help with garbage collection
        if (playWorker != null) {
            playWorker.stop();
            playWorker = null;
        }
        if (playWorkerThread != null) {
            playWorkerThread.interrupt();
            playWorkerThread = null;
        }
    }

    public String getServerPref() {
        return sharedPrefs.getString("server", "");
    }

    public int getPortPref() {
        return sharedPrefs.getInt("port", -1);
    }

    public boolean getAutostartPref() {
        return sharedPrefs.getBoolean("auto_start", false);
    }

    public void setPrefs(String server, int port, boolean checked) {
        sharedPrefs.edit()
                .putString("server", server)
                .putInt("port", port)
                .putBoolean("auto_start", checked)
                .apply();
    }

    public long getBufferHeadroom() {
        return headroomUsec;
    }

    public long getTargetLatency() {
        return latencyUsec;
    }

    public void setBufferUsec(long headroomUsec, long latencyUsec) {
        this.headroomUsec = headroomUsec;
        this.latencyUsec = latencyUsec;
        if (playWorker != null) {
            playWorker.setBufferUsec(headroomUsec, latencyUsec);
        }
        sharedPrefs.edit()
                .putLong(KEY_BUFFER_HEADROOM, this.headroomUsec)
                .putLong(KEY_TARGET_LATENCY, this.latencyUsec)
                .apply();
    }

    private long getBufferSizePref(String key, long defaultValue) {
        try {
            return sharedPrefs.getLong(key, defaultValue);
        } catch (ClassCastException ignored) {
        }
        // old buffer value saved as int
        int compatValue;
        try {
            compatValue = sharedPrefs.getInt(key, -1000);
        } catch (ClassCastException ignored) {
            return defaultValue;
        }
        if (compatValue == -1000) {
            return defaultValue;
        }
        // convert ms to us
        return compatValue * 1000L;
    }

    private void notifyState(@NonNull AudioPlayState state) {
        playState.setValue(state);
        notifManager.notify(NOTIFICATION, createNotification(state));
    }

    public boolean isStartable() {
        return getPlayState() == AudioPlayState.STOPPED;
    }

    public AudioPlayState getPlayState() {
        return playState.getValue();
    }

    @NonNull
    public LiveData<AudioPlayState> playState() {
        return playState;
    }

    public Throwable getError() {
        return playWorker == null ? null : playWorker.getError();
    }

    public void showNotification() {
        notifManager.notify(NOTIFICATION, createNotification(getPlayState()));
    }

    private Notification createNotification(@NonNull AudioPlayState state) {
        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, AudioFragment.class), PendingIntent.FLAG_IMMUTABLE);

        int statusResId;
        int buttonResId = R.string.btn_stop;
        switch (state) {
            case STOPPED:
                statusResId = R.string.playback_status_stopped;
                buttonResId = R.string.btn_play;
                break;
            case STARTING:
                statusResId = R.string.playback_status_starting;
                break;
            case BUFFERING:
                statusResId = R.string.playback_status_buffering;
                break;
            case STARTED:
                statusResId = R.string.playback_status_playing;
                break;
            case STOPPING:
                statusResId = R.string.playback_status_stopping;
                break;
            default:
                throw new IllegalArgumentException();
        }

        String contentText = getString(R.string.playback_service_status, getString(statusResId));

        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.notif_service);
        contentView.setTextViewText(R.id.toggleButton, getText(buttonResId));
        contentView.setOnClickPendingIntent(R.id.toggleButton, togglePendingIntent);
        contentView.setTextViewText(R.id.contentText, contentText);
        return new NotificationCompat.Builder(this, getString(R.string.service_notification_channel))
                .setCustomContentView(contentView)
                .setContentTitle(getText(R.string.playback_service_label))
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.ic_stat_ic_nh_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public static class LocalBinder extends Binder {
        private final AudioPlaybackService service;

        public LocalBinder(AudioPlaybackService service) {
            this.service = service;
        }

        public AudioPlaybackService getService() {
            return service;
        }
    }
    
}
