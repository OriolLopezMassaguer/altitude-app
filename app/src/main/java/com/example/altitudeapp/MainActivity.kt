package com.example.altitudeapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.altitudeapp.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
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
    
    private var currentLocationMarker: Marker? = null
    private val nearbyPassMarkers = mutableListOf<Marker>()
    private val passAdapter = PassAdapter()
    
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
                    
                    updateUI(lat, lon, alt, gain, passName)
                    updateMapLocation(lat, lon)
                    
                    nearbyPasses?.let { 
                        updateNearbyMarkers(it)
                        passAdapter.updateData(it, lat, lon)
                    }
                }
                AltitudeService.ACTION_LOG_UPDATE -> {
                    val message = intent.getStringExtra(AltitudeService.EXTRA_LOG_MESSAGE) ?: ""
                    appendLog(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDoubleTapGesture()
        setupMap()
        setupRecyclerView()

        binding.showNearbyPassesButton.setOnClickListener {
            toggleNearbyList()
        }

        appendLog("App launched")
        checkPermissionsAndStartService()
    }

    private fun setupRecyclerView() {
        binding.nearbyPassesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.nearbyPassesRecyclerView.adapter = passAdapter
    }

    private fun toggleNearbyList() {
        if (binding.nearbyPassesRecyclerView.visibility == View.GONE) {
            binding.nearbyPassesRecyclerView.visibility = View.VISIBLE
            binding.mapView.visibility = View.GONE
            binding.showNearbyPassesButton.text = "Show Map"
        } else {
            binding.nearbyPassesRecyclerView.visibility = View.GONE
            binding.mapView.visibility = View.VISIBLE
            binding.showNearbyPassesButton.text = "Show Nearby Passes"
        }
    }

    private fun updateNearbyMarkers(passes: List<MountainPass>) {
        for (marker in nearbyPassMarkers) {
            binding.mapView.overlays.remove(marker)
        }
        nearbyPassMarkers.clear()

        for (pass in passes) {
            val marker = Marker(binding.mapView)
            marker.position = GeoPoint(pass.latitude, pass.longitude)
            marker.title = pass.name
            marker.icon = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)
            binding.mapView.overlays.add(marker)
            nearbyPassMarkers.add(marker)
        }
        binding.mapView.invalidate()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.0)
        appendLog("Osmdroid Map initialized")
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val startPoint = GeoPoint(lat, lon)
        runOnUiThread {
            if (currentLocationMarker == null) {
                currentLocationMarker = Marker(binding.mapView)
                currentLocationMarker?.title = "You are here"
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
            appendLog("Logs visible")
        } else {
            binding.logScrollView.visibility = View.GONE
        }
    }

    private fun updateUI(lat: Double, lon: Double, alt: Double, gain: Double, passName: String?) {
        binding.coordinatesTextView.text = String.format(Locale.getDefault(), "Lat: %.5f, Lon: %.5f", lat, lon)
        binding.altitudeTextView.text = String.format(Locale.getDefault(), "%.1f m", alt)
        binding.gainTextView.text = String.format(Locale.getDefault(), "+%.2f m (1 min)", gain)
        
        if (!passName.isNullOrEmpty()) {
            binding.passTextView.text = passName
            binding.passTextView.visibility = View.VISIBLE
        } else {
            binding.passTextView.text = "No near pass"
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