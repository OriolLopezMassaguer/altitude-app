package com.example.altitudeapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class MountainPass(val name: String, val latitude: Double, val longitude: Double) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MountainPass) return false
        if (name != other.name) return false
        val dist = FloatArray(1)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, dist)
        return dist[0] < 100
    }
    override fun hashCode(): Int = name.hashCode()
}

data class TrackPoint(val lat: Double, val lon: Double, val alt: Double, val time: Long)

class AltitudeService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationHandlerThread: HandlerThread
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val altitudeHistory = LinkedList<Pair<Long, Double>>()
    private val ONE_MINUTE_MS = 1 * 60 * 1000L
    
    private val mountainPasses = mutableSetOf<MountainPass>()
    private val PROXIMITY_THRESHOLD_METERS = 500.0
    private val SEARCH_RADIUS_KM = 100.0
    private var lastLocation: Location? = null

    private val trackPoints = mutableListOf<TrackPoint>()
    private var currentTrackDate = ""
    private var locationUpdatesActive = false

    @Volatile private var currentSpeedLimit: Int = -1
    private var lastSpeedLimitQueryLat: Double = Double.NaN
    private var lastSpeedLimitQueryLon: Double = Double.NaN
    private var lastSpeedLimitQueryTime: Long = 0L
    private val SPEED_LIMIT_MIN_DISTANCE_M = 100f
    private val SPEED_LIMIT_MIN_INTERVAL_MS = 30_000L

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.example.altitudeapp.LOCATION_UPDATE"
        const val ACTION_LOG_UPDATE = "com.example.altitudeapp.LOG_UPDATE"
        const val ACTION_CLEAR_TRACK = "com.example.altitudeapp.CLEAR_TRACK"
        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_ALTITUDE_GAIN = "extra_altitude_gain"
        const val EXTRA_PASS_NAME = "extra_pass_name"
        const val EXTRA_NEARBY_PASSES = "extra_nearby_passes"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"
        const val EXTRA_SPEED_LIMIT = "extra_speed_limit"
        const val ACTION_ALTITUDE_UPDATE = ACTION_LOCATION_UPDATE
    }

    private val clearTrackReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLEAR_TRACK) {
                trackPoints.clear()
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val dir = getExternalFilesDir("tracks") ?: filesDir
                File(dir, "track_$today.gpx").delete()
                sendLog("Track cleared by user.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationHandlerThread = HandlerThread("LocationThread").also { it.start() }
        acquireWakeLock()
        loadMountainPasses()
        loadExistingTodayTrack()
        sendLog("Service Created. Initializing tracking...")
        androidx.core.content.ContextCompat.registerReceiver(
            this, clearTrackReceiver,
            android.content.IntentFilter(ACTION_CLEAR_TRACK),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    lastLocation = location
                    val lat = location.latitude
                    val lon = location.longitude
                    
                    val hasAlt = location.hasAltitude()
                    val alt = if (hasAlt) location.altitude else 0.0
                    
                    sendLog("Location received: Lat $lat, Lon $lon, Alt ${if (hasAlt) String.format("%.1f", alt) + "m" else "N/A"}")
                    
                    if (!hasAlt) {
                        sendLog("GPS Status: Waiting for altitude data...")
                    }
                    
                    var gain = 0.0
                    if (hasAlt) {
                        gain = updateAltitudeGain(alt)
                        updateNotification(alt)
                    }

                    recordTrackPoint(lat, lon, alt)

                    sendLog("Searching for passes near $lat, $lon...")
                    val nearbyPass = findNearbyPass(location)
                    if (nearbyPass != null) {
                        sendLog("Current Pass detected: ${nearbyPass.name}")
                    } else {
                        sendLog("No pass detected within ${PROXIMITY_THRESHOLD_METERS / 1000}km threshold.")
                    }

                    val passesInRadius = getPassesInRadius(location, SEARCH_RADIUS_KM)
                    sendLog("Found ${passesInRadius.size} passes within ${SEARCH_RADIUS_KM}km radius.")

                    fetchSpeedLimitIfNeeded(lat, lon)
                    broadcastLocation(lat, lon, alt, gain, nearbyPass?.name, passesInRadius)
                } else {
                    sendLog("Location update received but lastLocation is null.")
                }
            }
        }
    }

    private fun loadMountainPasses() {
        sendLog("Loading mountain passes from assets...")
        try {
            val files = assets.list("passes") ?: run {
                sendLog("Error: 'assets/passes' folder not found.")
                return
            }
            if (files.isEmpty()) {
                sendLog("Warning: 'assets/passes' folder is empty.")
            }
            for (fileName in files) {
                if (fileName.endsWith(".gpx")) {
                    sendLog("Parsing $fileName...")
                    val inputStream = assets.open("passes/$fileName")
                    val countBefore = mountainPasses.size
                    parseGpx(inputStream)
                    val countAfter = mountainPasses.size
                    sendLog("Finished $fileName. Added ${countAfter - countBefore} unique passes.")
                }
            }
            sendLog("Total unique passes loaded: ${mountainPasses.size}")
        } catch (e: Exception) {
            sendLog("Exception during pass loading: ${e.message}")
        }
    }

    private fun parseGpx(inputStream: InputStream) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                if (eventType == XmlPullParser.START_TAG && tagName == "wpt") {
                    val latAttr = parser.getAttributeValue(null, "lat")
                    val lonAttr = parser.getAttributeValue(null, "lon")
                    if (latAttr != null && lonAttr != null) {
                        val lat = latAttr.toDouble()
                        val lon = lonAttr.toDouble()
                        var nextEvent = parser.next()
                        while (!(nextEvent == XmlPullParser.END_TAG && parser.name == "wpt")) {
                            if (nextEvent == XmlPullParser.START_TAG && parser.name == "name") {
                                mountainPasses.add(MountainPass(parser.nextText(), lat, lon))
                            }
                            nextEvent = parser.next()
                        }
                    }
                }
                eventType = parser.next()
            }
            inputStream.close()
        } catch (e: Exception) {
            sendLog("GPX Parse error: ${e.message}")
        }
    }

    private fun findNearbyPass(location: Location): MountainPass? {
        for (pass in mountainPasses) {
            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, pass.latitude, pass.longitude, results)
            if (results[0] < PROXIMITY_THRESHOLD_METERS) return pass
        }
        return null
    }

    private fun getPassesInRadius(location: Location, radiusKm: Double): List<MountainPass> {
        val resultList = mutableListOf<MountainPass>()
        val radiusMeters = radiusKm * 1000.0
        for (pass in mountainPasses) {
            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, pass.latitude, pass.longitude, results)
            if (results[0] <= radiusMeters) resultList.add(pass)
        }
        return resultList
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AltitudeApp:ServiceWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours
        sendLog("WakeLock acquired.")
    }

    private fun updateAltitudeGain(currentAlt: Double): Double {
        val currentTime = System.currentTimeMillis()
        altitudeHistory.addLast(Pair(currentTime, currentAlt))
        while (altitudeHistory.isNotEmpty() && (currentTime - (altitudeHistory.firstOrNull()?.first ?: 0L)) > ONE_MINUTE_MS) {
            altitudeHistory.removeFirst()
        }
        if (altitudeHistory.size < 2) return 0.0
        var totalGain = 0.0
        for (i in 1 until altitudeHistory.size) {
            val diff = altitudeHistory[i].second - altitudeHistory[i-1].second
            if (diff > 0) totalGain += diff
        }
        return totalGain
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours
            sendLog("WakeLock re-acquired.")
        }
        
        startForegroundService()
        startLocationUpdates()
        DailyStartReceiver.scheduleWatchdog(this)
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "altitude_channel"
        val channelName = "PassMaster Tracking"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PassMaster Tracking")
            .setContentText("Altitude tracking active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
        sendLog("Foreground service started.")
    }

    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        sendLog("Requesting location updates (5 second interval)...")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(3000)
            .setWaitForAccurateLocation(false)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, locationHandlerThread.looper)
            locationUpdatesActive = true
            sendLog("Location updates active.")
            // Immediately emit the last known location so UI shows something right away
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && lastLocation == null) {
                    sendLog("Using cached last location while waiting for GPS fix...")
                    locationCallback.onLocationResult(LocationResult.create(listOf(location)))
                }
            }
        } else {
            sendLog("Error: Missing location permissions!")
        }
    }

    private fun broadcastLocation(lat: Double, lon: Double, alt: Double, gain: Double, passName: String?, nearbyPasses: List<MountainPass>) {
        val intent = Intent(ACTION_LOCATION_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_LATITUDE, lat)
        intent.putExtra(EXTRA_LONGITUDE, lon)
        intent.putExtra(EXTRA_ALTITUDE, alt)
        intent.putExtra(EXTRA_ALTITUDE_GAIN, gain)
        intent.putExtra(EXTRA_PASS_NAME, passName)
        intent.putExtra(EXTRA_NEARBY_PASSES, ArrayList(nearbyPasses))
        intent.putExtra(EXTRA_SPEED_LIMIT, currentSpeedLimit)
        sendBroadcast(intent)
    }

    private fun fetchSpeedLimitIfNeeded(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSpeedLimitQueryTime < SPEED_LIMIT_MIN_INTERVAL_MS) return
        if (!lastSpeedLimitQueryLat.isNaN()) {
            val dist = FloatArray(1)
            Location.distanceBetween(lastSpeedLimitQueryLat, lastSpeedLimitQueryLon, lat, lon, dist)
            if (dist[0] < SPEED_LIMIT_MIN_DISTANCE_M) return
        }
        lastSpeedLimitQueryLat = lat
        lastSpeedLimitQueryLon = lon
        lastSpeedLimitQueryTime = now

        Thread {
            try {
                val query = "[out:json][timeout:5];way(around:50,$lat,$lon)[maxspeed];out tags;"
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val conn = URL("https://overpass-api.de/api/interpreter?data=$encoded")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", "MotoPass/1.0")
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val elements = JSONObject(response).getJSONArray("elements")
                var limit = -1
                for (i in 0 until elements.length()) {
                    val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                    val parsed = parseMaxSpeed(tags.optString("maxspeed", ""))
                    if (parsed > 0) { limit = parsed; break }
                }
                currentSpeedLimit = limit
                sendLog("Speed limit: ${if (limit > 0) "$limit km/h" else "unknown"}")
            } catch (e: Exception) {
                sendLog("Speed limit fetch error: ${e.message}")
            }
        }.start()
    }

    private fun parseMaxSpeed(value: String): Int {
        if (value.isBlank()) return -1
        value.toIntOrNull()?.let { return it }
        val parts = value.trim().split(" ")
        if (parts.size == 2) {
            val num = parts[0].toIntOrNull() ?: return -1
            return when (parts[1].lowercase()) {
                "mph" -> (num * 1.60934).toInt()
                else -> num
            }
        }
        return when (value.lowercase()) {
            "es:urban", "de:urban", "fr:urban", "it:urban", "pt:urban",
            "be:urban", "nl:urban", "pl:urban", "cz:urban", "sk:urban" -> 50
            "es:rural", "fr:rural", "it:rural", "pt:rural",
            "be:rural", "nl:rural", "pl:rural", "cz:rural", "sk:rural" -> 90
            "de:rural" -> 100
            "es:motorway", "fr:motorway", "it:motorway", "pt:motorway",
            "be:motorway", "nl:motorway", "de:motorway" -> 120
            "es:living_street", "de:living_street", "fr:living_street",
            "it:living_street", "pt:living_street" -> 20
            "walk", "foot" -> 10
            else -> -1
        }
    }

    private fun sendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val intent = Intent(ACTION_LOG_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_LOG_MESSAGE, "[$timestamp] $message")
        sendBroadcast(intent)
    }

    private fun updateNotification(altitude: Double) {
        val notificationText = String.format("Current Altitude: %.1f m", altitude)
        val channelId = "altitude_channel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MotoPass Active")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(1, notification)
    }

    private fun loadExistingTodayTrack() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir("tracks") ?: filesDir
        val file = File(dir, "track_$today.gpx")
        if (!file.exists()) {
            currentTrackDate = today
            return
        }
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFormat.timeZone = TimeZone.getTimeZone("UTC")
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(file.inputStream(), "UTF-8")
            var eventType = parser.eventType
            var lat = 0.0; var lon = 0.0; var alt = 0.0; var time = 0L
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when {
                    eventType == XmlPullParser.START_TAG && parser.name == "trkpt" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        alt = 0.0; time = 0L
                    }
                    eventType == XmlPullParser.START_TAG && parser.name == "ele" ->
                        alt = parser.nextText().toDoubleOrNull() ?: 0.0
                    eventType == XmlPullParser.START_TAG && parser.name == "time" ->
                        time = try { isoFormat.parse(parser.nextText())?.time ?: 0L } catch (_: Exception) { 0L }
                    eventType == XmlPullParser.END_TAG && parser.name == "trkpt" ->
                        if (lat != 0.0 || lon != 0.0) trackPoints.add(TrackPoint(lat, lon, alt, time))
                }
                eventType = parser.next()
            }
            currentTrackDate = today
            sendLog("Resumed today's track: ${trackPoints.size} existing points loaded.")
        } catch (e: Exception) {
            sendLog("Could not load existing track: ${e.message}")
            currentTrackDate = today
        }
    }

    private fun recordTrackPoint(lat: Double, lon: Double, alt: Double) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (today != currentTrackDate) {
            if (trackPoints.isNotEmpty() && currentTrackDate.isNotEmpty()) {
                writeGpxFile(currentTrackDate)
            }
            trackPoints.clear()
            currentTrackDate = today
        }
        trackPoints.add(TrackPoint(lat, lon, alt, System.currentTimeMillis()))
        writeGpxFile(currentTrackDate)
    }

    private fun writeGpxFile(date: String) {
        if (trackPoints.isEmpty()) return
        try {
            val dir = getExternalFilesDir("tracks") ?: filesDir
            dir.mkdirs()
            val file = File(dir, "track_$date.gpx")
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFormat.timeZone = TimeZone.getTimeZone("UTC")
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<gpx version=\"1.1\" creator=\"MotoPass\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            sb.append("  <trk>\n")
            sb.append("    <name>Track $date</name>\n")
            sb.append("    <trkseg>\n")
            for (pt in trackPoints) {
                sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lon}\">\n")
                if (pt.alt != 0.0) sb.append("        <ele>${String.format(Locale.US, "%.1f", pt.alt)}</ele>\n")
                sb.append("        <time>${isoFormat.format(Date(pt.time))}</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n")
            sb.append("  </trk>\n")
            sb.append("</gpx>\n")
            file.writeText(sb.toString())
            sendLog("Track saved: ${file.name} (${trackPoints.size} pts)")
        } catch (e: Exception) {
            sendLog("Error saving track: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(clearTrackReceiver)
        if (trackPoints.isNotEmpty() && currentTrackDate.isNotEmpty()) {
            writeGpxFile(currentTrackDate)
        }
        sendLog("Service Destroyed.")
        wakeLock?.let { if (it.isHeld) it.release() }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesActive = false
        locationHandlerThread.quitSafely()
    }
}