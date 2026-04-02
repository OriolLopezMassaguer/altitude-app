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
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.altitudeapp.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var gestureDetector: GestureDetector
    private lateinit var sharedPreferences: SharedPreferences
    
    private var currentLocationMarker: Marker? = null
    private val nearbyPassMarkers = mutableListOf<Marker>()
    private val passAdapter = PassAdapter { pass -> onPassInListClicked(pass) }
    
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0

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
                    saveLastPosition(lat, lon)
                    
                    updateUI(lat, lon, alt, gain, passName, nearbyPasses)
                    updateMapLocation(lat, lon)
                    
                    nearbyPasses?.let { 
                        updateNearbyMarkers(it, passName)
                        passAdapter.updateData(it, lat, lon)
                        updatePassListVisibility(it)
                    }
                }
                AltitudeService.ACTION_LOG_UPDATE -> {
                    val message = intent.getStringExtra(AltitudeService.EXTRA_LOG_MESSAGE) ?: ""
                    appendLog(message)
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

        binding.languageButton?.setOnClickListener { showLanguageMenu(it) }

        loadLastPosition()
        appendLog(getString(R.string.app_launched))
        checkPermissionsAndStartService()
    }

    private fun onPassInListClicked(pass: MountainPass) {
        // Center map on the pass and show it
        val passPoint = GeoPoint(pass.latitude, pass.longitude)
        binding.mapView.controller.animateTo(passPoint)
        binding.mapView.controller.setZoom(14.0)
        
        // Hide list and show map
        binding.nearbyPassesRecyclerView.visibility = View.GONE
        binding.mapView.visibility = View.VISIBLE
        binding.showNearbyPassesButton.text = getString(R.string.show_nearby_passes)
        
        // Try to find its marker and show its info window
        nearbyPassMarkers.find { it.position.latitude == pass.latitude && it.position.longitude == pass.longitude }?.showInfoWindow()
    }

    private fun updatePassListVisibility(passes: List<MountainPass>) {
        if (passes.isEmpty()) {
            binding.showNearbyPassesButton.visibility = View.GONE
            binding.nearbyPassesRecyclerView.visibility = View.GONE
            binding.mapView.visibility = View.VISIBLE
        } else {
            binding.showNearbyPassesButton.visibility = View.VISIBLE
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

    private fun saveLastPosition(lat: Double, lon: Double) {
        sharedPreferences.edit().apply {
            putFloat("last_lat", lat.toFloat())
            putFloat("last_lon", lon.toFloat())
            apply()
        }
    }

    private fun loadLastPosition() {
        val lat = sharedPreferences.getFloat("last_lat", 0.0f).toDouble()
        val lon = sharedPreferences.getFloat("last_lon", 0.0f).toDouble()
        if (lat != 0.0 && lon != 0.0) {
            lastLat = lat
            lastLon = lon
            updateMapLocation(lat, lon)
            updateUI(lat, lon, 0.0, 0.0, null, null)
            appendLog(getString(R.string.cached_pos_loaded))
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
        for (marker in nearbyPassMarkers) {
            binding.mapView.overlays.remove(marker)
        }
        nearbyPassMarkers.clear()

        for (pass in passes) {
            val marker = Marker(binding.mapView)
            marker.position = GeoPoint(pass.latitude, pass.longitude)
            marker.title = pass.name
            
            if (pass.name == currentPassName) {
                marker.icon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default_focused_base)
                marker.showInfoWindow()
            } else {
                marker.icon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)
            }

            marker.setOnMarkerClickListener { m, _ ->
                val gmmIntentUri = Uri.parse("google.navigation:q=${m.position.latitude},${m.position.longitude}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${m.position.latitude},${m.position.longitude}"))
                    startActivity(webIntent)
                }
                true
            }
            
            binding.mapView.overlays.add(marker)
            nearbyPassMarkers.add(marker)
        }
        binding.mapView.invalidate()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.0)
        appendLog(getString(R.string.map_initialized))
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val startPoint = GeoPoint(lat, lon)
        runOnUiThread {
            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(binding.mapView)
                currentLocationMarker?.title = getString(R.string.you_are_here)
                currentLocationMarker?.icon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.person)
                binding.mapView.overlays.add(currentLocationMarker)
                binding.mapView.controller.setCenter(startPoint)
            }
            currentLocationMarker?.position = startPoint
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
        }
        
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        unregisterReceiver(receiver)
    }

    private fun checkPermissionsAndStartService() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startAltitudeService()
        }
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
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAltitudeService()
            }
        }
    }
}