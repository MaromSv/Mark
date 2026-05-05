package com.example.emergency.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.example.emergency.agent.Tool
import com.example.emergency.agent.ToolResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * GPS location tool - gets the user's current location.
 */
class GpsLocationTool(private val context: Context) {
    
    fun getTool(): Tool = Tool(
        name = "get_location",
        description = "Returns the user's current GPS coordinates as 'lat, lon'. " +
            "Use only when the user asks where they are. For navigation or 'take me to X', " +
            "use route_to instead - get_location no longer fakes turn-by-turn directions.",
        execute = ::execute,
    )

    private suspend fun execute(@Suppress("UNUSED_PARAMETER") params: Map<String, String>): ToolResult {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            return ToolResult(
                success = false,
                data = "",
                error = "Location permission not granted. Unable to access GPS."
            )
        }

        return try {
            val location = getLocation()
            if (location != null) {
                val lat = String.format("%.6f", location.latitude)
                val lon = String.format("%.6f", location.longitude)
                ToolResult(success = true, data = "Current location: $lat, $lon")
            } else {
                ToolResult(
                    success = false,
                    data = "",
                    error = "GPS not ready (no recent fix).",
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                data = "",
                error = "Error getting location: ${e.message}"
            )
        }
    }
    
    private suspend fun getLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()
        
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                continuation.resume(location)
            }.addOnFailureListener {
                continuation.resume(null)
            }
        } catch (e: SecurityException) {
            continuation.resume(null)
        }
        
        continuation.invokeOnCancellation {
            cancellationTokenSource.cancel()
        }
    }
}
