package io.github.traushb.actuali.services.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.traushb.actuali.services.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = Logger("Location")

data class Coordinates(val latitude: Double, val longitude: Double)

enum class LocationAuthStatus { NotDetermined, Granted, Denied }

sealed class LocationError : Exception() {
    object PermissionDenied : LocationError()
    object Unavailable : LocationError()
}

/** Abstraction over the platform location service so tests can inject a fake. */
interface PositionSource {
    fun authorizationStatus(): LocationAuthStatus

    suspend fun fetchPosition(): Coordinates
}

/**
 * Async facade over the location service with a 60 s position cache (matching
 * the upstream web client's LocationService.CACHE_DURATION). Permission is
 * requested by the UI, which owns the activity the system dialog needs.
 */
class LocationProvider(private val source: PositionSource, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val lock = Mutex()
    private var cached: Pair<Coordinates, Long>? = null

    fun authorizationStatus(): LocationAuthStatus = source.authorizationStatus()

    /** Current position, served from cache when fresh. Throws when permission is denied or no fix is available. */
    suspend fun currentPosition(): Coordinates = lock.withLock {
        if (source.authorizationStatus() != LocationAuthStatus.Granted) throw LocationError.PermissionDenied
        cached?.let { (position, at) -> if (now() - at < CACHE_DURATION_MILLIS) return@withLock position }
        source.fetchPosition().also { cached = it to now() }
    }

    companion object {
        private const val CACHE_DURATION_MILLIS = 60_000L

        /** Installed at launch with the platform source. */
        lateinit var shared: LocationProvider
    }
}

/** The platform source: one-shot fixes only — no continuous monitoring, no background use. */
class AndroidPositionSource(private val context: Context) : PositionSource {
    override fun authorizationStatus(): LocationAuthStatus {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        // Android can't tell "never asked" from "refused" without an activity;
        // the UI's permission request resolves that.
        return if (granted) LocationAuthStatus.Granted else LocationAuthStatus.Denied
    }

    /** Raced against a 15 s timeout (matching the upstream web client) so the hardware can't be held on indefinitely. */
    @SuppressLint("MissingPermission") // checked by LocationProvider before every fetch
    override suspend fun fetchPosition(): Coordinates = try {
        withTimeout(15_000) {
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                ?: throw LocationError.Unavailable
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (location != null) {
                        continuation.resume(Coordinates(location.latitude, location.longitude))
                    } else {
                        continuation.resumeWithException(LocationError.Unavailable)
                    }
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        logger.info("Location fetch timed out after 15 s")
        throw LocationError.Unavailable
    }
}
