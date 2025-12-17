package com.example.altitudeapp

// AltitudeService.kt
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class AltitudeService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    if (location.hasAltitude()) {
                        updateNotification(location.altitude)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        startLocationUpdates()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "altitude_channel"
        val channelName = "Altitude Service"
        // This block is crucial for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tracking Altitude")
            .setContentText("Altitude: initializing...")
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure this icon exists!
            .build()

        // The '1' is the notification ID.
        startForeground(1, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permissions should be checked in the Activity before starting the service
            stopSelf()
            return
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun updateNotification(altitude: Double) {
        val notificationText = String.format("Altitude: %.2f meters", altitude)
        val channelId = "altitude_channel"

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tracking Altitude")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher) // Use your app's icon
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}