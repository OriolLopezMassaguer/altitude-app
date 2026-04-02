package com.example.altitudeapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class AltitudeService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val altitudeHistory = LinkedList<Pair<Long, Double>>()
    private val FIVE_MINUTES_MS = 5 * 60 * 1000L

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.example.altitudeapp.LOCATION_UPDATE"
        const val ACTION_LOG_UPDATE = "com.example.altitudeapp.LOG_UPDATE"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_ALTITUDE_GAIN = "extra_altitude_gain"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
        
        const val ACTION_ALTITUDE_UPDATE = ACTION_LOCATION_UPDATE
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        sendLog("Service Created and WakeLock acquired")

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val hasAlt = location.hasAltitude()
                    val alt = if (hasAlt) location.altitude else 0.0
                    
                    var gain = 0.0
                    if (hasAlt) {
                        gain = updateAltitudeGain(alt)
                        updateNotification(alt)
                    }
                    
                    broadcastLocation(lat, lon, alt, gain)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AltitudeApp:ServiceWakeLock")
        wakeLock?.acquire()
    }

    private fun updateAltitudeGain(currentAlt: Double): Double {
        val currentTime = System.currentTimeMillis()
        altitudeHistory.addLast(Pair(currentTime, currentAlt))
        
        while (altitudeHistory.isNotEmpty() && (currentTime - altitudeHistory.first().first) > FIVE_MINUTES_MS) {
            altitudeHistory.removeFirst()
        }
        
        if (altitudeHistory.size < 2) return 0.0
        
        var totalGain = 0.0
        for (i in 1 until altitudeHistory.size) {
            val diff = altitudeHistory[i].second - altitudeHistory[i-1].second
            if (diff > 0) {
                totalGain += diff
            }
        }
        return totalGain
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sendLog("Service Started")
        startForegroundService()
        startLocationUpdates()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "altitude_channel"
        val channelName = "Altitude Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tracking Altitude")
            .setContentText("Altitude: initializing...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60000)
            .setMinUpdateIntervalMillis(30000)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendLog("Error: Location permission missing")
            stopSelf()
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        sendLog("Location updates active (every 1 minute)")
    }

    private fun broadcastLocation(lat: Double, lon: Double, alt: Double, gain: Double) {
        val intent = Intent(ACTION_LOCATION_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_LATITUDE, lat)
        intent.putExtra(EXTRA_LONGITUDE, lon)
        intent.putExtra(EXTRA_ALTITUDE, alt)
        intent.putExtra(EXTRA_ALTITUDE_GAIN, gain)
        sendBroadcast(intent)
    }

    private fun sendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val intent = Intent(ACTION_LOG_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_LOG_MESSAGE, "[$timestamp] $message")
        sendBroadcast(intent)
    }

    private fun updateNotification(altitude: Double) {
        val notificationText = String.format("Altitude: %.2f meters", altitude)
        val channelId = "altitude_channel"

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tracking Altitude")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                sendLog("WakeLock released")
            }
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}