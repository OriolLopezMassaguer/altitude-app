package com.example.altitudeapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class PassAdapter(private val onPassClick: (MountainPass) -> Unit) : RecyclerView.Adapter<PassAdapter.ViewHolder>() {

    private var passes: List<MountainPass> = emptyList()
    private var userLat: Double = 0.0
    private var userLon: Double = 0.0

    fun updateData(newPasses: List<MountainPass>, lat: Double, lon: Double) {
        // Sort by distance
        passes = newPasses.sortedBy { pass ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lat, lon, pass.latitude, pass.longitude, results)
            results[0]
        }
        userLat = lat
        userLon = lon
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pass, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pass = passes[position]
        holder.name.text = pass.name
        
        val results = FloatArray(1)
        android.location.Location.distanceBetween(userLat, userLon, pass.latitude, pass.longitude, results)
        val distanceKm = results[0] / 1000.0
        holder.distance.text = String.format(Locale.getDefault(), "%.1f km", distanceKm)
        
        holder.itemView.setOnClickListener {
            onPassClick(pass)
        }
    }

    override fun getItemCount() = passes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.passName)
        val distance: TextView = view.findViewById(R.id.passDistance)
    }
}