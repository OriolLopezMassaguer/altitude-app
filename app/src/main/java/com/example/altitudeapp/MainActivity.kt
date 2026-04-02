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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.altitudeapp.databinding.ActivityMainBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private lateinit var gestureDetector: GestureDetector
    
    private var googleMap: GoogleMap? = null
    private var currentLocationMarker: Marker? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AltitudeService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra(AltitudeService.EXTRA_LATITUDE, 0.0)
                    val lon = intent.getDoubleExtra(AltitudeService.EXTRA_LONGITUDE, 0.0)
                    val alt = intent.getDoubleExtra(AltitudeService.EXTRA_ALTITUDE, 0.0)
                    val gain = intent.getDoubleExtra(AltitudeService.EXTRA_ALTITUDE_GAIN, 0.0)
                    
                    updateUI(lat, lon, alt, gain)
                    updateMapLocation(lat, lon)
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDoubleTapGesture()
        setupMap()

        appendLog("App launched")
        checkPermissionsAndStartService()
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap?.uiSettings?.isZoomControlsEnabled = false
        googleMap?.uiSettings?.isMyLocationButtonEnabled = false
        appendLog("Map is ready")
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val position = LatLng(lat, lon)
        runOnUiThread {
            if (googleMap != null) {
                if (currentLocationMarker == null) {
                    currentLocationMarker = googleMap?.addMarker(MarkerOptions().position(position).title("Current Position"))
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 15f))
                } else {
                    currentLocationMarker?.position = position
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLng(position))
                }
            }
        }
    }

    private fun setupDoubleTapGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleLogs()
                return true
            }
        })

        binding.mainLayout.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    private fun toggleLogs() {
        if (binding.logScrollView.visibility == View.GONE) {
            binding.logScrollView.visibility = View.VISIBLE
            appendLog("Logs visible")
        } else {
            binding.logScrollView.visibility = View.GONE
        }
    }

    private fun updateUI(lat: Double, lon: Double, alt: Double, gain: Double) {
        binding.coordinatesTextView.text = String.format(Locale.getDefault(), "Lat: %.5f, Lon: %.5f", lat, lon)
        binding.altitudeTextView.text = String.format(Locale.getDefault(), "%.1f m", alt)
        binding.gainTextView.text = String.format(Locale.getDefault(), "+%.2f m", gain)
        
        updatePlaceName(lat, lon)
    }

    private fun updatePlaceName(lat: Double, lon: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val placeName = address.locality ?: address.subAdminArea ?: address.adminArea ?: "Unknown Place"
                        runOnUiThread { binding.placeTextView.text = placeName }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    binding.placeTextView.text = address.locality ?: address.subAdminArea ?: address.adminArea ?: "Unknown Place"
                }
            }
        } catch (e: IOException) {
            appendLog("Geocoder error: ${e.message}")
        }
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
        val filter = IntentFilter()
        filter.addAction(AltitudeService.ACTION_LOCATION_UPDATE)
        filter.addAction(AltitudeService.ACTION_LOG_UPDATE)
        
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
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