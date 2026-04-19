package com.example.altitudeapp

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.altitudeapp.databinding.ActivityTracksBinding
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class TrackFile(val file: File, val date: String, val pointCount: Int, val fileSize: Long)

class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private val tracks = mutableListOf<TrackFile>()
    private lateinit var adapter: TrackAdapter

    private val importGpxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importTrack(it) }
    }

    private val backupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { backupAllTracks(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tracks_title)

        binding.tracksRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TrackAdapter(
            tracks,
            onShare = { shareTrack(it) },
            onDownload = { downloadTrack(it) },
            onDelete = { track, position -> confirmDelete(track, position) }
        )
        binding.tracksRecyclerView.adapter = adapter

        binding.fabBackup.setOnClickListener {
            if (tracks.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_tracks_recorded), Toast.LENGTH_SHORT).show()
            } else {
                backupFolderLauncher.launch(null)
            }
        }

        reloadTracks()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_tracks, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import_track) {
            importGpxLauncher.launch(arrayOf("*/*"))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun reloadTracks() {
        tracks.clear()
        tracks.addAll(loadTracks())
        adapter.notifyDataSetChanged()
        if (tracks.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.tracksRecyclerView.visibility = View.GONE
        } else {
            binding.emptyText.visibility = View.GONE
            binding.tracksRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadTracks(): List<TrackFile> {
        val dir = getExternalFilesDir("tracks") ?: filesDir
        val files = dir.listFiles { f -> f.name.startsWith("track_") && f.name.endsWith(".gpx") }
            ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .map { file ->
                val date = file.name.removePrefix("track_").removeSuffix(".gpx")
                TrackFile(file, date, countTrackPoints(file), file.length())
            }
    }

    private fun countTrackPoints(file: File): Int {
        var count = 0
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(file.inputStream(), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") count++
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return count
    }

    private fun shareTrack(track: TrackFile) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", track.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_track)))
    }

    private fun downloadTrack(track: TrackFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, track.file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        track.file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, getString(R.string.track_saved_downloads, track.file.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.track_download_error), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.track_download_error), Toast.LENGTH_SHORT).show()
            }
        } else {
            shareTrack(track)
        }
    }

    private fun backupAllTracks(treeUri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: run {
            Toast.makeText(this, getString(R.string.backup_error), Toast.LENGTH_SHORT).show()
            return
        }
        var count = 0
        var errors = 0
        for (track in tracks) {
            try {
                val dest = tree.findFile(track.file.name)
                    ?: tree.createFile("application/gpx+xml", track.file.name)
                if (dest != null) {
                    contentResolver.openOutputStream(dest.uri)?.use { out ->
                        track.file.inputStream().use { it.copyTo(out) }
                    }
                    count++
                } else { errors++ }
            } catch (_: Exception) { errors++ }
        }
        val msg = if (errors == 0) getString(R.string.backup_success, count)
                  else getString(R.string.backup_partial, count, errors)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun importTrack(uri: Uri) {
        val date = extractDateFromGpx(uri)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir("tracks") ?: filesDir
        val destFile = File(dir, "track_$date.gpx")
        when {
            destFile.exists() && date == today -> mergeGpxIntoFile(uri, destFile, date)
            destFile.exists() -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_gpx))
                .setMessage(getString(R.string.track_import_exists, TrackAdapter.friendlyDateStatic(date, this)))
                .setPositiveButton(getString(R.string.track_import_overwrite)) { _, _ -> copyGpxFile(uri, destFile, date) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> copyGpxFile(uri, destFile, date)
        }
    }

    private fun mergeGpxIntoFile(uri: Uri, destFile: File, date: String) {
        try {
            val existing = parseTrkPts(destFile.inputStream())
            val imported = contentResolver.openInputStream(uri)?.use { parseTrkPts(it) } ?: emptyList()
            val merged = (existing + imported)
                .distinctBy { it.time ?: "${it.lat}${it.lon}" }
                .sortedBy { it.time ?: "" }
            writeMergedGpx(destFile, merged)
            sendReloadBroadcast()
            reloadTracks()
            Toast.makeText(this, getString(R.string.track_merged, merged.size), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.gpx_import_error), Toast.LENGTH_SHORT).show()
        }
    }

    private data class TrkPt(val lat: String, val lon: String, val ele: String?, val time: String?)

    private fun parseTrkPts(stream: java.io.InputStream): List<TrkPt> {
        val points = mutableListOf<TrkPt>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        var lat = ""; var lon = ""; var ele: String? = null; var time: String? = null
        var inTrkPt = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when {
                eventType == XmlPullParser.START_TAG && parser.name == "trkpt" -> {
                    inTrkPt = true
                    lat = parser.getAttributeValue(null, "lat") ?: ""
                    lon = parser.getAttributeValue(null, "lon") ?: ""
                    ele = null; time = null
                }
                inTrkPt && eventType == XmlPullParser.START_TAG && parser.name == "ele" ->
                    ele = parser.nextText()
                inTrkPt && eventType == XmlPullParser.START_TAG && parser.name == "time" ->
                    time = parser.nextText()
                eventType == XmlPullParser.END_TAG && parser.name == "trkpt" -> {
                    if (lat.isNotEmpty() && lon.isNotEmpty()) points.add(TrkPt(lat, lon, ele, time))
                    inTrkPt = false
                }
            }
            eventType = parser.next()
        }
        return points
    }

    private fun writeMergedGpx(file: File, points: List<TrkPt>) {
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"MotoPass\">\n<trk><trkseg>\n")
            for (pt in points) {
                w.write("<trkpt lat=\"${pt.lat}\" lon=\"${pt.lon}\">")
                pt.ele?.let { w.write("<ele>$it</ele>") }
                pt.time?.let { w.write("<time>$it</time>") }
                w.write("</trkpt>\n")
            }
            w.write("</trkseg></trk>\n</gpx>")
        }
    }

    private fun sendReloadBroadcast() {
        sendBroadcast(Intent(AltitudeService.ACTION_RELOAD_TRACK).apply { setPackage(packageName) })
    }

    private fun extractDateFromGpx(uri: Uri): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            contentResolver.openInputStream(uri)?.use { stream ->
                parser.setInput(stream, "UTF-8")
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "time") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            val text = parser.text.trim()
                            val dateStr = text.take(10)
                            if (dateStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return@use dateStr
                        }
                    }
                    eventType = parser.next()
                }
                null
            }
        } catch (_: Exception) { null }
    }

    private fun copyGpxFile(uri: Uri, destFile: File, date: String) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            reloadTracks()
            Toast.makeText(this, getString(R.string.track_imported, TrackAdapter.friendlyDateStatic(date, this)), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.gpx_import_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(track: TrackFile, position: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_track))
            .setMessage(getString(R.string.delete_track_confirm, TrackAdapter.friendlyDateStatic(track.date, this)))
            .setPositiveButton(getString(R.string.delete_track)) { _, _ -> deleteTrack(track, position) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTrack(track: TrackFile, position: Int) {
        track.file.delete()
        // If this is today's track, also tell the service to clear its in-memory buffer
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (track.date == today) {
            val clearIntent = Intent(AltitudeService.ACTION_CLEAR_TRACK).apply {
                setPackage(packageName)
            }
            sendBroadcast(clearIntent)
        }
        tracks.removeAt(position)
        adapter.notifyItemRemoved(position)
        if (tracks.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.tracksRecyclerView.visibility = View.GONE
        }
    }
}

class TrackAdapter(
    private val tracks: MutableList<TrackFile>,
    private val onShare: (TrackFile) -> Unit,
    private val onDownload: (TrackFile) -> Unit,
    private val onDelete: (TrackFile, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    companion object {
        fun friendlyDateStatic(dateStr: String, ctx: android.content.Context): String {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse(dateStr) ?: return dateStr
                val today = sdf.format(Date())
                val yesterday = sdf.format(Date(System.currentTimeMillis() - 86_400_000L))
                when (dateStr) {
                    today -> ctx.getString(R.string.today)
                    yesterday -> ctx.getString(R.string.yesterday)
                    else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(date)
                }
            } catch (_: Exception) { dateStr }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(tracks[position], position, onShare, onDownload, onDelete)

    override fun getItemCount() = tracks.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dateLabel: TextView = view.findViewById(R.id.trackDateLabel)
        private val statsLabel: TextView = view.findViewById(R.id.trackStatsLabel)
        private val shareButton: Button = view.findViewById(R.id.trackShareButton)
        private val downloadButton: Button = view.findViewById(R.id.trackDownloadButton)
        private val deleteButton: Button = view.findViewById(R.id.trackDeleteButton)

        fun bind(
            track: TrackFile,
            position: Int,
            onShare: (TrackFile) -> Unit,
            onDownload: (TrackFile) -> Unit,
            onDelete: (TrackFile, Int) -> Unit
        ) {
            val ctx = itemView.context
            dateLabel.text = friendlyDateStatic(track.date, ctx)
            val kb = (track.fileSize / 1024).coerceAtLeast(1)
            statsLabel.text = ctx.getString(R.string.track_stats, track.pointCount, kb)
            shareButton.setOnClickListener { onShare(track) }
            downloadButton.setOnClickListener { onDownload(track) }
            deleteButton.setOnClickListener { onDelete(track, adapterPosition) }
        }
    }
}
