package com.sabbir.dbmtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TrackingService extends Service {
    private static final int LOCATION_UPDATE_INTERVAL = 2000; // 2 seconds
    private boolean isTracking = false;
    private boolean isPaused = false;
    private TelephonyManager telephonyManager;
    private LocationManager locationManager;
    private FileWriter csvWriter;
    private Handler handler = new Handler();

    private int sim1SignalStrength = -999;
    private int sim2SignalStrength = -999;

    private String sim1OperatorName = "Unknown";
    private String sim2OperatorName = "Unknown";

    private Runnable dataLoggerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTracking && !isPaused) {
                writeDataToCSV();
                handler.postDelayed(this, LOCATION_UPDATE_INTERVAL);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        startSignalStrengthListener();

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
            }
            csvWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
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
                    break;
                case "pause":
                    isPaused = true;
                    handler.removeCallbacks(dataLoggerRunnable);
                    break;
                case "resume":
                    isPaused = false;
                    handler.removeCallbacks(dataLoggerRunnable);
                    handler.post(dataLoggerRunnable);
                    break;
                case "stop":
                    isTracking = false;
                    handler.removeCallbacks(dataLoggerRunnable);
                    stopSelf();
                    break;
            }
        }

        return START_STICKY;
    }

    private void writeDataToCSV() {
        try {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            Location location = getLocation();
            String[] simData = getSimSignalStrength();

            if (location != null) {
                csvWriter.append(String.format("%s,%.6f,%.6f,%s,%s,%s,%s\n",
                        timestamp, location.getLatitude(), location.getLongitude(),
                        simData[0], simData[1], simData[2], simData[3]));
                csvWriter.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Location getLocation() {
        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e("TrackingService", "Location permissions not granted!");
                return null;
            }
            return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void startSignalStrengthListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();

            if (subscriptionInfoList != null) {
                for (SubscriptionInfo subInfo : subscriptionInfoList) {
                    int slotIndex = subInfo.getSimSlotIndex();
                    String operatorName = subInfo.getDisplayName().toString();

                    if (slotIndex == 0) {
                        sim1OperatorName = operatorName;
                    } else if (slotIndex == 1) {
                        sim2OperatorName = operatorName;
                    }
                }
            }
        }

        telephonyManager.listen(new PhoneStateListener() {
            @Override
            public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                super.onSignalStrengthsChanged(signalStrength);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    SubscriptionManager sm = SubscriptionManager.from(getApplicationContext());
                    List<SubscriptionInfo> subscriptionInfoList = sm.getActiveSubscriptionInfoList();

                    if (subscriptionInfoList != null) {
                        for (SubscriptionInfo subInfo : subscriptionInfoList) {
                            int slotIndex = subInfo.getSimSlotIndex();
                            int subId = subInfo.getSubscriptionId();

                            TelephonyManager tmForSub = getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
                            if (tmForSub != null) {
                                List<CellSignalStrength> cellSignalStrengths = tmForSub.getSignalStrength().getCellSignalStrengths();
                                if (!cellSignalStrengths.isEmpty()) {
                                    int dbm = cellSignalStrengths.get(0).getDbm();
                                    if (slotIndex == 0) {
                                        sim1SignalStrength = dbm;
                                    } else if (slotIndex == 1) {
                                        sim2SignalStrength = dbm;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
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