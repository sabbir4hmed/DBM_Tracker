package com.sabbir.dbmtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
// Remove the import statement for android.location.LocationRequest
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.CellSignalStrength;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.google.android.gms.location.Priority;
import com.google.android.gms.location.LocationRequest;

public class TrackingService extends Service {
    private static final int LOCATION_UPDATE_INTERVAL = 2000;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "DBMTrackerChannel";

    private boolean isTracking = false;
    private boolean isPaused = false;
    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private FileWriter csvWriter;
    private Handler handler = new Handler();
    private PowerManager.WakeLock wakeLock;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Location currentLocation;
    private LocationRequest locationRequest;

    private int sim1SignalStrength = -999;
    private int sim2SignalStrength = -999;
    private String sim1OperatorName = "Unknown";
    private String sim2OperatorName = "Unknown";
    private PhoneStateListener sim1Listener;
    private PhoneStateListener sim2Listener;

    private Runnable dataLoggerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTracking && !isPaused) {
                writeDataToCSV();
            }
            handler.postDelayed(this, LOCATION_UPDATE_INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "DBMTracker::DataLoggingWakeLock");
        wakeLock.acquire();

        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createLocationCallback();
        startLocationUpdates();
        startSignalStrengthListener();

        initializeCSVWriter();
    }

    private void initializeCSVWriter() {
        try {
            File documentsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File appDirectory = new File(documentsPath, "DBMTracker");
            if (!appDirectory.exists()) {
                appDirectory.mkdirs();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File file = new File(appDirectory, "tracking_data_" + timeStamp + ".csv");
            boolean isNewFile = !file.exists();
            csvWriter = new FileWriter(file, true);
            if (isNewFile) {
                csvWriter.append("Timestamp,Latitude,Longitude,SIM1 Name,SIM1 Signal Strength (dBm),SIM2 Name,SIM2 Signal Strength (dBm)\n");
                csvWriter.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DBM Tracker Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DBM Tracker Active")
                .setContentText("Tracking signal strength and location")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return builder.build();
    }

    private void createLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null) {
                    currentLocation = locationResult.getLastLocation();
                }
            }
        };
    }

    private void startLocationUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationRequest = new LocationRequest.Builder(2000L)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(1000L)
                    .setMaxUpdateDelayMillis(2000L)
                    .build();
        } else {
            locationRequest = LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setInterval(2000)
                    .setFastestInterval(1000);
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void startSignalStrengthListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());

            sim1Listener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);
                    updateSim1SignalStrength(signalStrength);
                }
            };

            sim2Listener = new PhoneStateListener() {
                @Override
                public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                    super.onSignalStrengthsChanged(signalStrength);
                    updateSim2SignalStrength(signalStrength);
                }
            };

            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionInfoList != null) {
                for (SubscriptionInfo subInfo : subscriptionInfoList) {
                    int slotIndex = subInfo.getSimSlotIndex();
                    String operatorName = subInfo.getDisplayName().toString();
                    int subId = subInfo.getSubscriptionId();

                    if (slotIndex == 0) {
                        sim1OperatorName = operatorName;
                        TelephonyManager tm1 = getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
                        tm1.listen(sim1Listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                    } else if (slotIndex == 1) {
                        sim2OperatorName = operatorName;
                        TelephonyManager tm2 = getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
                        tm2.listen(sim2Listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                    }
                }
            }
        }
    }

    private void updateSim1SignalStrength(SignalStrength signalStrength) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
            if (!cellSignalStrengths.isEmpty()) {
                sim1SignalStrength = cellSignalStrengths.get(0).getDbm();
                Log.d("SignalStrength", "SIM1 DBM updated: " + sim1SignalStrength);
            }
        }
    }

    private void updateSim2SignalStrength(SignalStrength signalStrength) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
            if (!cellSignalStrengths.isEmpty()) {
                sim2SignalStrength = cellSignalStrengths.get(0).getDbm();
                Log.d("SignalStrength", "SIM2 DBM updated: " + sim2SignalStrength);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = intent.getStringExtra("command");

        if (command != null) {
            switch (command) {
                case "start":
                    isTracking = true;
                    isPaused = false;
                    handler.removeCallbacks(dataLoggerRunnable);
                    handler.post(dataLoggerRunnable);
                    if (!wakeLock.isHeld()) {
                        wakeLock.acquire();
                    }
                    break;
                case "pause":
                    isPaused = true;
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    break;
                case "resume":
                    isPaused = false;
                    if (!wakeLock.isHeld()) {
                        wakeLock.acquire();
                    }
                    break;
                case "stop":
                    isTracking = false;
                    handler.removeCallbacks(dataLoggerRunnable);
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    stopSelf();
                    break;
            }
        }

        return START_STICKY;
    }

    private void writeDataToCSV() {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String[] simData = getSimSignalStrength();

            String dataRow = String.format("%s,%s,%s,%s,%s,%s,%s\n",
                    timestamp,
                    currentLocation != null ? String.format("%.6f", currentLocation.getLatitude()) : "0.0",
                    currentLocation != null ? String.format("%.6f", currentLocation.getLongitude()) : "0.0",
                    simData[0], simData[1], simData[2], simData[3]);

            csvWriter.append(dataRow);
            csvWriter.flush();
            Log.d("TrackingService", "Data written: " + dataRow);

        } catch (IOException e) {
            Log.e("TrackingService", "Error writing to CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String[] getSimSignalStrength() {
        String[] data = {
                sim1OperatorName,
                sim1SignalStrength == -999 ? "N/A" : String.valueOf(sim1SignalStrength),
                sim2OperatorName,
                sim2SignalStrength == -999 ? "N/A" : String.valueOf(sim2SignalStrength)
        };
        return data;
    }

    @Override
    public void onDestroy() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (sim1Listener != null) {
            telephonyManager.listen(sim1Listener, PhoneStateListener.LISTEN_NONE);
        }
        if (sim2Listener != null) {
            telephonyManager.listen(sim2Listener, PhoneStateListener.LISTEN_NONE);
        }
        super.onDestroy();
        handler.removeCallbacks(dataLoggerRunnable);
        try {
            if (csvWriter != null) {
                csvWriter.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}