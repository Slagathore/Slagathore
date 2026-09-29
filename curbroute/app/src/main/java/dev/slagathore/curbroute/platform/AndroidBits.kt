package dev.slagathore.curbroute.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.slagathore.curbroute.core.LatLng
import dev.slagathore.curbroute.core.geo.GeoHit
import dev.slagathore.curbroute.core.geo.Geocoder
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The phone's built-in geocoder (backed by Google on Play-services phones). Free, no key.
 * Used as a fallback after the Census geocoder.
 */
class AndroidGeocoder(context: Context) : Geocoder {
    private val platform = android.location.Geocoder(context, Locale.US)

    override fun geocode(address: String): GeoHit? {
        if (!android.location.Geocoder.isPresent()) return null
        // The blocking version is deprecated in favor of a callback API (Android 13+). We're already on a
        // background thread, so blocking is simpler and works on every Android version.
        @Suppress("DEPRECATION")
        val results = try {
            platform.getFromLocationName(address, 1)
        } catch (e: IOException) {
            null
        }
        val hit = results?.firstOrNull() ?: return null
        return GeoHit(LatLng(hit.latitude, hit.longitude), "android")
    }
}

object StartLocation {
    fun hasPermission(context: Context) = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Current GPS fix, or null if there's no permission / no fix within 10 s. */
    @SuppressLint("MissingPermission") // checked by hasPermission() right above
    suspend fun current(context: Context): LatLng? {
        if (!hasPermission(context)) return null
        return try {
            withTimeoutOrNull(10_000) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                    .await()
            }?.let { LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            null
        }
    }
}

object MapsLauncher {
    private const val GOOGLE_MAPS = "com.google.android.apps.maps"

    /** Opens a Google Maps directions link in the Maps app (falls back to any browser/maps app). */
    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(intent).setPackage(GOOGLE_MAPS))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(intent)
        }
    }
}

object Photos {
    /** A fresh, private file the camera app can write into (shared via our FileProvider). */
    fun newPhotoUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "sheet-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
