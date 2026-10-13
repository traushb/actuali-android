package io.github.traushb.actuali.services.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure location math/formatting, mirroring upstream
 * `loot-core/src/shared/location-utils.ts` and `shared/constants.ts`.
 */
object LocationUtils {
    /** Upstream DEFAULT_MAX_DISTANCE_METERS: nearby-payee radius and the dedupe radius for recording new locations. */
    const val DEFAULT_MAX_DISTANCE_METERS: Double = 500.0

    /** Haversine distance in meters (Earth radius 6371e3, as upstream). */
    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val dPhi = (lat2 - lat1) * Math.PI / 180
        val dLambda = (lon2 - lon1) * Math.PI / 180
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Upstream format: "328ft | 100m". */
    fun formatDistance(meters: Double): String = "${(meters * 3.28084).roundToLong()}ft | ${meters.roundToLong()}m"

    /** Upstream coordinate validation (createPayeeLocation / getNearbyPayees). */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
