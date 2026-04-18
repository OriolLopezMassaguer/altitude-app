package com.example.altitudeapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.altitudeapp.databinding.ActivityMainBinding
import androidx.activity.result.contract.ActivityResultContracts
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val BG_LOCATION_PERMISSION_REQUEST_CODE = 2
    private lateinit var gestureDetector: GestureDetector
    private lateinit var sharedPreferences: SharedPreferences
    
    private var currentLocationMarker: Marker? = null
    private var hasReceivedFirstFix = false
    private val nearbyPassMarkers = mutableListOf<Marker>()
    private var lastRenderedPassNames: Set<String> = emptySet()
    private var passIconDrawable: android.graphics.drawable.Drawable? = null
    private val trackPoints = mutableListOf<GeoPoint>()
    private var trackPolyline: Polyline? = null
    private val importedTrackPoints = mutableListOf<GeoPoint>()
    private var importedTrackPolyline: Polyline? = null
    private val historicalPolylines = mutableListOf<Polyline>()
    private var routesVisible = false

    private val importGpxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadImportedGpxTrack(it) }
    }
    private val LABEL_ZOOM_THRESHOLD = 12.0
    private val passAdapter = PassAdapter { pass -> onPassInListClicked(pass) }
    
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var lastAlt: Double = 0.0
    private var lastGain: Double = 0.0
    private var lastPassName: String? = null
    private var lastNearbyPasses: ArrayList<MountainPass>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AltitudeService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra(AltitudeService.EXTRA_LATITUDE, 0.0)
                    val lon = intent.getDoubleExtra(AltitudeService.EXTRA_LONGITUDE, 0.0)
                    val alt = intent.getDoubleExtra(AltitudeService.EXTRA_ALTITUDE, 0.0)
                    val gain = intent.getDoubleExtra(AltitudeService.EXTRA_ALTITUDE_GAIN, 0.0)
                    val passName = intent.getStringExtra(AltitudeService.EXTRA_PASS_NAME)
                    val nearbyPasses = intent.getSerializableExtra(AltitudeService.EXTRA_NEARBY_PASSES) as? ArrayList<MountainPass>

                    lastLat = lat
                    lastLon = lon
                    lastAlt = alt
                    lastGain = gain
                    lastPassName = passName
                    lastNearbyPasses = nearbyPasses?.let { ArrayList(it) }
                    saveLastState(lat, lon, alt, gain, passName)

                    updateUI(lat, lon, alt, gain, passName, nearbyPasses)
                    appendTrackPoint(lat, lon)
                    updateMapLocation(lat, lon)

                    nearbyPasses?.let {
                        val sorted = it.sortedBy { pass ->
                            FloatArray(1).also { d ->
                                Location.distanceBetween(lat, lon, pass.latitude, pass.longitude, d)
                            }[0]
                        }
                        updateNearbyMarkers(sorted.take(20), passName)
                        passAdapter.updateData(sorted, lat, lon)
                        updatePassListVisibility(sorted)
                    }
                }
                AltitudeService.ACTION_LOG_UPDATE -> {
                    val message = intent.getStringExtra(AltitudeService.EXTRA_LOG_MESSAGE) ?: ""
                    appendLog(message)
                }
                AltitudeService.ACTION_CLEAR_TRACK -> {
                    trackPoints.clear()
                    trackPolyline?.setPoints(emptyList())
                    binding.mapView.invalidate()
                    appendLog(getString(R.string.track_cleared))
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(newBase)
        val lang = prefs.getString("language", "ca") ?: "ca"
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        OsmConfig.getInstance().load(this, sharedPreferences)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDoubleTapGesture()
        setupMap()
        setupRecyclerView()

        binding.showNearbyPassesButton.setOnClickListener {
            toggleNearbyList()
        }

        binding.showRoutesButton?.setOnClickListener {
            toggleRoutes()
        }

        binding.shareTrackButton.setOnClickListener { shareTrack() }
        binding.tracksButton?.setOnClickListener {
            startActivity(Intent(this, TracksActivity::class.java))
        }
        binding.importGpxButton?.setOnClickListener {
            importGpxLauncher.launch(arrayOf("*/*"))
        }

        binding.languageButton?.setOnClickListener { showLanguageMenu(it) }

        if (savedInstanceState != null) {
            restoreFromBundle(savedInstanceState)
        } else {
            loadLastPosition()
        }
        DailyStartReceiver.scheduleDailyAlarm(this)
        appendLog(getString(R.string.app_launched))
        checkPermissionsAndStartService()
    }

    private fun onPassInListClicked(pass: MountainPass) {
        navigateToLocation(pass.latitude, pass.longitude)
    }

    private fun updatePassListVisibility(passes: List<MountainPass>) {
        if (passes.isEmpty()) {
            binding.nearbyPassesRecyclerView.visibility = View.GONE
            binding.mapView.visibility = View.VISIBLE
        }
    }

    private fun showLanguageMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, "English")
        popup.menu.add(0, 2, 1, "Español")
        popup.menu.add(0, 3, 2, "Català")
        
        popup.setOnMenuItemClickListener { item ->
            val lang = when (item.itemId) {
                1 -> "en"
                2 -> "es"
                3 -> "ca"
                else -> "ca"
            }
            setLanguage(lang)
            true
        }
        popup.show()
    }

    private fun setLanguage(lang: String) {
        sharedPreferences.edit().putString("language", lang).apply()
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble("lat", lastLat)
        outState.putDouble("lon", lastLon)
        outState.putDouble("alt", lastAlt)
        outState.putDouble("gain", lastGain)
        outState.putString("passName", lastPassName)
        lastNearbyPasses?.let { outState.putSerializable("nearbyPasses", it) }
        outState.putDoubleArray("track_lats", trackPoints.map { it.latitude }.toDoubleArray())
        outState.putDoubleArray("track_lons", trackPoints.map { it.longitude }.toDoubleArray())
        outState.putDoubleArray("imp_lats", importedTrackPoints.map { it.latitude }.toDoubleArray())
        outState.putDoubleArray("imp_lons", importedTrackPoints.map { it.longitude }.toDoubleArray())
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreFromBundle(bundle: Bundle) {
        lastLat = bundle.getDouble("lat", 0.0)
        lastLon = bundle.getDouble("lon", 0.0)
        lastAlt = bundle.getDouble("alt", 0.0)
        lastGain = bundle.getDouble("gain", 0.0)
        lastPassName = bundle.getString("passName")
        lastNearbyPasses = bundle.getSerializable("nearbyPasses") as? ArrayList<MountainPass>
        val lats = bundle.getDoubleArray("track_lats") ?: doubleArrayOf()
        val lons = bundle.getDoubleArray("track_lons") ?: doubleArrayOf()
        trackPoints.clear()
        lats.zip(lons.toList()).mapTo(trackPoints) { (lat, lon) -> GeoPoint(lat, lon) }
        trackPolyline?.setPoints(trackPoints)
        val impLats = bundle.getDoubleArray("imp_lats") ?: doubleArrayOf()
        val impLons = bundle.getDoubleArray("imp_lons") ?: doubleArrayOf()
        importedTrackPoints.clear()
        impLats.zip(impLons.toList()).mapTo(importedTrackPoints) { (lat, lon) -> GeoPoint(lat, lon) }
        importedTrackPolyline?.setPoints(importedTrackPoints)

        if (lastLat != 0.0 || lastLon != 0.0) {
            updateMapLocation(lastLat, lastLon)
            updateUI(lastLat, lastLon, lastAlt, lastGain, lastPassName, lastNearbyPasses)
            lastNearbyPasses?.let { passes ->
                updateNearbyMarkers(passes, lastPassName)
                passAdapter.updateData(passes, lastLat, lastLon)
                updatePassListVisibility(passes)
            }
        }
    }

    private fun saveLastState(lat: Double, lon: Double, alt: Double, gain: Double, passName: String?) {
        sharedPreferences.edit().apply {
            putFloat("last_lat", lat.toFloat())
            putFloat("last_lon", lon.toFloat())
            putFloat("last_alt", alt.toFloat())
            putFloat("last_gain", gain.toFloat())
            if (passName != null) putString("last_pass_name", passName) else remove("last_pass_name")
            apply()
        }
    }

    private fun loadLastPosition() {
        val lat = sharedPreferences.getFloat("last_lat", 0.0f).toDouble()
        val lon = sharedPreferences.getFloat("last_lon", 0.0f).toDouble()
        if (lat != 0.0 && lon != 0.0) {
            lastLat = lat
            lastLon = lon
            lastAlt = sharedPreferences.getFloat("last_alt", 0.0f).toDouble()
            lastGain = sharedPreferences.getFloat("last_gain", 0.0f).toDouble()
            lastPassName = sharedPreferences.getString("last_pass_name", null)
            updateMapLocation(lat, lon)
            updateUI(lat, lon, lastAlt, lastGain, lastPassName, null)
            appendLog(getString(R.string.cached_pos_loaded))
        }
        loadTodayTrack()
    }

    private fun loadTodayTrack() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir("tracks") ?: filesDir
        val file = File(dir, "track_$today.gpx")
        if (!file.exists()) return
        try {
            val points = mutableListOf<GeoPoint>()
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(file.inputStream(), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val ptLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val ptLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (ptLat != null && ptLon != null) points.add(GeoPoint(ptLat, ptLon))
                }
                eventType = parser.next()
            }
            if (points.isNotEmpty()) {
                trackPoints.addAll(points)
                trackPolyline?.setPoints(trackPoints)
                appendLog("Track loaded: ${points.size} pts from $today")
            }
        } catch (e: Exception) {
            appendLog("Track load error: ${e.message}")
        }
    }

    private fun shareTrack() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir("tracks") ?: filesDir
        val file = File(dir, "track_$today.gpx")
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.no_track_to_share), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_track)))
    }

    private fun loadImportedGpxTrack(uri: Uri) {
        try {
            val points = mutableListOf<GeoPoint>()
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            contentResolver.openInputStream(uri)?.use { stream ->
                parser.setInput(stream, null)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG &&
                        (parser.name == "trkpt" || parser.name == "wpt")) {
                        val ptLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val ptLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (ptLat != null && ptLon != null) points.add(GeoPoint(ptLat, ptLon))
                    }
                    eventType = parser.next()
                }
            }
            if (points.isEmpty()) {
                Toast.makeText(this, getString(R.string.gpx_import_error), Toast.LENGTH_SHORT).show()
                return
            }
            importedTrackPoints.clear()
            importedTrackPoints.addAll(points)
            importedTrackPolyline?.setPoints(importedTrackPoints)

            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            val box = BoundingBox(maxLat, maxLon, minLat, minLon)
            binding.mapView.post {
                binding.mapView.zoomToBoundingBox(box, true, 64)
                binding.mapView.invalidate()
            }

            Toast.makeText(this, getString(R.string.gpx_imported, points.size), Toast.LENGTH_SHORT).show()
            appendLog("GoPro GPX imported: ${points.size} pts")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.gpx_import_error), Toast.LENGTH_SHORT).show()
            appendLog("GPX import error: ${e.message}")
        }
    }

    private fun setupRecyclerView() {
        binding.nearbyPassesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.nearbyPassesRecyclerView.adapter = passAdapter
    }

    private fun toggleNearbyList() {
        if (binding.nearbyPassesRecyclerView.visibility == View.GONE) {
            binding.nearbyPassesRecyclerView.visibility = View.VISIBLE
            binding.mapView.visibility = View.GONE
            binding.showNearbyPassesButton.text = getString(R.string.show_map)
        } else {
            binding.nearbyPassesRecyclerView.visibility = View.GONE
            binding.mapView.visibility = View.VISIBLE
            binding.showNearbyPassesButton.text = getString(R.string.show_nearby_passes)
        }
    }

    private fun updateNearbyMarkers(passes: List<MountainPass>, currentPassName: String?) {
        val newNames = passes.mapTo(mutableSetOf()) { it.name }
        if (newNames == lastRenderedPassNames) return
        lastRenderedPassNames = newNames

        nearbyPassMarkers.forEach { it.closeInfoWindow() }
        binding.mapView.overlays.removeAll(nearbyPassMarkers)
        nearbyPassMarkers.clear()

        for (pass in passes) {
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(pass.latitude, pass.longitude)
                title = pass.name
                icon = passIconDrawable
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { m, _ ->
                    navigateToLocation(m.position.latitude, m.position.longitude)
                    true
                }
            }
            nearbyPassMarkers.add(marker)
        }
        binding.mapView.overlays.addAll(nearbyPassMarkers)
        binding.mapView.post {
            nearbyPassMarkers.forEach { it.showInfoWindow() }
            binding.mapView.invalidate()
        }
    }

    private fun navigateToLocation(lat: Double, lon: Double) {
        // Collect unique apps across multiple URI schemes; keyed by package to avoid duplicates
        val options = linkedMapOf<String, Pair<String, Intent>>()

        fun queryScheme(uriStr: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { ri ->
                    options.getOrPut(ri.activityInfo.packageName) {
                        ri.loadLabel(packageManager).toString() to
                            Intent(intent).apply { setPackage(ri.activityInfo.packageName) }
                    }
                }
        }

        queryScheme("geo:$lat,$lon")
        queryScheme("here-route://mylocation/$lat,$lon/drive") // HERE Maps / BMW
        queryScheme("bmwmotorrad://?lat=$lat&lon=$lon")        // BMW own scheme

        // Last-resort: try every known BMW package name explicitly
        val bmwPackages = listOf(
            "com.bmw.ConnectedRide",
            "de.bmw.motorrad.connected",
            "de.bmw.connected.mobile20.row",
            "de.bmw.connected.mobile20.na",
            "com.bmwgroup.connected.bmw"
        )
        if (options.none { it.key in bmwPackages }) {
            for (pkg in bmwPackages) {
                val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
                val label = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { continue }
                options[pkg] = label to launch
                break
            }
        }

        val sorted = options.values.sortedBy { it.first }
        if (sorted.isEmpty()) {
            appendLog("Navigation: opening system chooser for $lat, $lon")
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon")),
                getString(R.string.navigate_with)
            ))
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.navigate_with))
            .setItems(sorted.map { it.first }.toTypedArray()) { _, i ->
                appendLog("Navigation: opening ${sorted[i].first} → $lat, $lon")
                startActivity(sorted[i].second)
            }
            .show()
    }

    private fun isPackageInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun appendTrackPoint(lat: Double, lon: Double) {
        trackPoints.add(GeoPoint(lat, lon))
        trackPolyline?.setPoints(trackPoints)
        binding.mapView.invalidate()
    }

    private fun toggleRoutes() {
        if (routesVisible) {
            historicalPolylines.forEach { binding.mapView.overlays.remove(it) }
            historicalPolylines.clear()
            binding.mapView.invalidate()
            binding.showRoutesButton?.text = getString(R.string.show_routes)
            routesVisible = false
        } else {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val dir = getExternalFilesDir("tracks") ?: filesDir
            val files = dir.listFiles { f ->
                f.name.startsWith("track_") && f.name.endsWith(".gpx") && !f.name.contains(today)
            }?.sortedBy { it.name } ?: emptyList()

            val allPoints = mutableListOf<GeoPoint>()
            for (file in files) {
                val pts = readGpxPoints(file)
                if (pts.isEmpty()) continue
                allPoints.addAll(pts)
                val polyline = Polyline(binding.mapView).apply {
                    setPoints(pts)
                    outlinePaint.color = android.graphics.Color.parseColor("#E64A19")
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.isAntiAlias = true
                    outlinePaint.alpha = 180
                }
                historicalPolylines.add(polyline)
                binding.mapView.overlays.add(polyline)
            }

            if (allPoints.isNotEmpty()) {
                val minLat = allPoints.minOf { it.latitude }
                val maxLat = allPoints.maxOf { it.latitude }
                val minLon = allPoints.minOf { it.longitude }
                val maxLon = allPoints.maxOf { it.longitude }
                binding.mapView.post {
                    binding.mapView.zoomToBoundingBox(
                        BoundingBox(maxLat, maxLon, minLat, minLon), true, 64
                    )
                }
            }
            binding.mapView.invalidate()
            binding.showRoutesButton?.text = getString(R.string.hide_routes)
            routesVisible = true

            if (files.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_past_routes), Toast.LENGTH_SHORT).show()
                routesVisible = false
                binding.showRoutesButton?.text = getString(R.string.show_routes)
            }
        }
    }

    private fun readGpxPoints(file: java.io.File): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(file.inputStream(), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) points.add(GeoPoint(lat, lon))
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return points
    }

    private fun setupMap() {
        passIconDrawable = ContextCompat.getDrawable(this, R.drawable.ic_mountain_pass)
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.0)

        trackPolyline = Polyline(binding.mapView).apply {
            outlinePaint.color = android.graphics.Color.parseColor("#3F51B5")
            outlinePaint.strokeWidth = 6f
            outlinePaint.isAntiAlias = true
        }
        binding.mapView.overlays.add(0, trackPolyline)

        importedTrackPolyline = Polyline(binding.mapView).apply {
            outlinePaint.color = android.graphics.Color.parseColor("#FF6D00")
            outlinePaint.strokeWidth = 5f
            outlinePaint.isAntiAlias = true
        }
        binding.mapView.overlays.add(1, importedTrackPolyline)

        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                updateMarkersForZoom(event?.zoomLevel?.toDouble() ?: binding.mapView.zoomLevelDouble)
                return false
            }
        })
        appendLog(getString(R.string.map_initialized))
    }

    private fun updateMarkersForZoom(zoom: Double) {
        binding.mapView.invalidate()
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val startPoint = GeoPoint(lat, lon)
        runOnUiThread {
            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(binding.mapView)
                currentLocationMarker?.title = getString(R.string.you_are_here)
                currentLocationMarker?.icon = ContextCompat.getDrawable(this, R.drawable.ic_motorcycle)
                currentLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                binding.mapView.overlays.add(currentLocationMarker)
            }
            currentLocationMarker?.position = startPoint
            // Only auto-center on the first real GPS fix; after that the user can pan freely
            if (!hasReceivedFirstFix) {
                hasReceivedFirstFix = true
                binding.mapView.controller.setZoom(14.0)
                binding.mapView.controller.setCenter(startPoint)
            }
            binding.mapView.invalidate()
        }
    }

    private fun setupDoubleTapGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleLogs()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun toggleLogs() {
        if (binding.logScrollView.visibility == View.GONE) {
            binding.logScrollView.visibility = View.VISIBLE
            appendLog(getString(R.string.logs_visible))
        } else {
            binding.logScrollView.visibility = View.GONE
        }
    }

    private fun updateUI(lat: Double, lon: Double, alt: Double, gain: Double, passName: String?, nearbyPasses: List<MountainPass>?) {
        binding.coordinatesTextView.text = getString(R.string.coordinates_format, lat, lon)
        binding.altitudeTextView.text = getString(R.string.altitude_format, alt)
        binding.gainTextView.text = getString(R.string.gain_format, gain)
        
        if (!passName.isNullOrEmpty()) {
            val dist = FloatArray(1)
            var distanceToPass = ""
            nearbyPasses?.find { it.name == passName }?.let { pass ->
                Location.distanceBetween(lat, lon, pass.latitude, pass.longitude, dist)
                distanceToPass = " (${String.format(Locale.getDefault(), "%.1f", dist[0] / 1000.0)} km)"
            }
            binding.passTextView.text = passName + distanceToPass
            binding.passTextView.visibility = View.VISIBLE
        } else {
            binding.passTextView.text = getString(R.string.no_near_pass)
            binding.passTextView.visibility = View.VISIBLE
        }
        
        updatePlaceName(lat, lon)
    }

    private fun updatePlaceName(lat: Double, lon: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val placeName = address.locality ?: address.subAdminArea ?: address.adminArea ?: "Unknown"
                        runOnUiThread { binding.placeTextView.text = placeName }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    binding.placeTextView.text = address.locality ?: address.subAdminArea ?: address.adminArea ?: "Unknown"
                }
            }
        } catch (e: IOException) { }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMsg = if (message.startsWith("[")) message else "[$timestamp] $message"
        
        runOnUiThread {
            binding.logTextView.append("\n$formattedMsg")
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        val filter = IntentFilter().apply {
            addAction(AltitudeService.ACTION_LOCATION_UPDATE)
            addAction(AltitudeService.ACTION_LOG_UPDATE)
            addAction(AltitudeService.ACTION_CLEAR_TRACK)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Restart service if it was killed while the app was in the background
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startAltitudeService()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        unregisterReceiver(receiver)
    }

    private fun checkPermissionsAndStartService() {
        val foregroundPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            foregroundPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val toRequest = foregroundPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            onForegroundPermissionsGranted()
        }
    }

    private fun onForegroundPermissionsGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // On Android 11+, background location must be requested separately
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                BG_LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            requestBatteryOptimizationExemption()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
        startAltitudeService()
    }

    private fun startAltitudeService() {
        val serviceIntent = Intent(this, AltitudeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val fineGranted = grantResults.isNotEmpty() &&
                    grantResults[permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        .takeIf { it >= 0 } ?: 0] == PackageManager.PERMISSION_GRANTED
                if (fineGranted) onForegroundPermissionsGranted()
            }
            BG_LOCATION_PERMISSION_REQUEST_CODE -> {
                // Background location granted or denied — either way, proceed
                requestBatteryOptimizationExemption()
            }
        }
    }
}