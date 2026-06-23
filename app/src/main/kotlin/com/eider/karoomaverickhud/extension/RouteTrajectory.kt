package com.eider.karoomaverickhud.extension

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A geographic point in degrees. */
data class LatLng(val lat: Double, val lng: Double)

/**
 * One projected path point in a **heading-up metre frame**: `rightM` is metres to the rider's
 * right, `forwardM` is metres ahead along their heading. The renderer scales these to pixels by the
 * current zoom (you sit at bottom-centre looking up, so +forward draws upward and +right rightward).
 */
data class MetersPoint(val rightM: Float, val forwardM: Float)

/**
 * Turns the navigation route into a glanceable "what's the road ahead doing" trajectory for the
 * glasses — chiefly to read curves on descents before you reach them. Decodes the route polyline,
 * finds where you are on it, and projects the next stretch into a heading-up metre frame. All pure
 * maths (unit-tested); the EvsKit `Polyline` rendering and zoom live in `HudScreen`.
 */
object RouteTrajectory {
    /** Furthest the extension projects (m). The renderer shows a zoom-selected slice of this. */
    const val MAX_LOOKAHEAD_M = 500.0

    /** Decimation cap on the projected path — keeps the per-frame BLE push to the glasses light. */
    const val MAX_POINTS = 28

    /** Sentinel "page id" marking the trajectory map page in a layout (not a Karoo stream). */
    const val PAGE_MARKER = "maverick.trajectory"

    /** Grade (%) at or below which the trajectory map auto-pins — a descent worth reading corners on. */
    const val DESCENT_GRADE = -3.0

    private const val M_PER_DEG_LAT = 110_540.0
    private const val M_PER_DEG_LNG_EQ = 111_320.0

    /**
     * Decode a Google encoded polyline (default precision 5, as the Karoo route polylines use) into
     * lat/lng points. Standard algorithm: zig-zag varint deltas, 5 bits per chunk.
     */
    fun decode(encoded: String, precision: Int = 5): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            index = readDelta(encoded, index) { lat += it }
            index = readDelta(encoded, index) { lng += it }
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    private inline fun readDelta(s: String, startIndex: Int, apply: (Int) -> Unit): Int {
        var index = startIndex
        var shift = 0
        var bits = 0
        var b: Int
        do {
            b = s[index++].code - 63
            bits = bits or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        apply(if (bits and 1 != 0) (bits shr 1).inv() else (bits shr 1))
        return index
    }

    /** Index of the route point nearest [pos] (planar-metre approximation). -1 for an empty route. */
    fun nearestIndex(route: List<LatLng>, pos: LatLng): Int {
        if (route.isEmpty()) return -1
        val cosLat = cos(Math.toRadians(pos.lat))
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in route.indices) {
            val e = (route[i].lng - pos.lng) * M_PER_DEG_LNG_EQ * cosLat
            val n = (route[i].lat - pos.lat) * M_PER_DEG_LAT
            val d = e * e + n * n
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * Project the route ahead of [pos] into a heading-up metre frame: from the nearest route point,
     * walking in the travel direction ([reversed] flips index order), up to [MAX_LOOKAHEAD_M], each
     * point rotated so [headingDeg] (0 = north) maps to +forward. The rider is the leading (0,0)
     * point. Empty when the route/heading is missing. Decimated to [MAX_POINTS].
     */
    fun project(
        route: List<LatLng>,
        pos: LatLng,
        headingDeg: Double?,
        reversed: Boolean = false,
    ): List<MetersPoint> {
        if (route.size < 2 || headingDeg == null) return emptyList()
        val start = nearestIndex(route, pos)
        if (start < 0) return emptyList()

        val cosLat = cos(Math.toRadians(pos.lat))
        val theta = Math.toRadians(headingDeg)
        val sinT = sin(theta)
        val cosT = cos(theta)
        val step = if (reversed) -1 else 1

        val pts = ArrayList<MetersPoint>()
        pts.add(MetersPoint(0f, 0f)) // the rider
        var acc = 0.0
        var prev = pos
        var i = start
        while (i in route.indices && acc < MAX_LOOKAHEAD_M) {
            val p = route[i]
            val e = (p.lng - pos.lng) * M_PER_DEG_LNG_EQ * cosLat
            val n = (p.lat - pos.lat) * M_PER_DEG_LAT
            // Rotate (east, north) into heading-up: forward along heading, right 90° clockwise of it.
            val forward = e * sinT + n * cosT
            val right = e * cosT - n * sinT
            // Drop points clearly behind the rider (nearest index can sit slightly back).
            if (forward >= -10.0) pts.add(MetersPoint(right.toFloat(), forward.toFloat()))
            val pe = (p.lng - prev.lng) * M_PER_DEG_LNG_EQ * cosLat
            val pn = (p.lat - prev.lat) * M_PER_DEG_LAT
            acc += hypot(pe, pn)
            prev = p
            i += step
        }
        return decimate(pts, MAX_POINTS)
    }

    /** Evenly thin a point list to at most [max] points, always keeping the first and last. */
    private fun decimate(pts: List<MetersPoint>, max: Int): List<MetersPoint> {
        if (pts.size <= max) return pts
        val out = ArrayList<MetersPoint>(max)
        val stride = (pts.size - 1).toDouble() / (max - 1)
        var idx = 0.0
        repeat(max) {
            out.add(pts[idx.toInt().coerceIn(0, pts.size - 1)])
            idx += stride
        }
        return out
    }
}
