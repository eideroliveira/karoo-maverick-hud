package com.eider.karoomaverickhud.extension

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the route trajectory: polyline decode and the heading-up projection geometry. */
class RouteTrajectoryTest {

    // ---- decode ----

    @Test
    fun decodesTheCanonicalGooglePolyline() {
        // The reference example from Google's polyline algorithm docs.
        val pts = RouteTrajectory.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5); assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5); assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5); assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test
    fun decodeEmptyIsEmpty() {
        assertTrue(RouteTrajectory.decode("").isEmpty())
    }

    // ---- projection ----

    private fun has(pts: List<MetersPoint>, right: Float, forward: Float, tol: Float = 1.5f) =
        pts.any { abs(it.rightM - right) < tol && abs(it.forwardM - forward) < tol }

    @Test
    fun riderIsTheLeadingOrigin() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0))
        val pts = RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = 0.0)
        assertEquals(MetersPoint(0f, 0f), pts.first())
    }

    @Test
    fun straightNorthHeadingNorthProjectsForward() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0), LatLng(0.002, 0.0))
        val pts = RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = 0.0)
        // ~110.5 m north should be straight ahead (right ~ 0).
        assertTrue("expected a forward point near 110m", has(pts, right = 0f, forward = 110.5f, tol = 2f))
    }

    @Test
    fun headingEastAlignsEastwardRouteToForward() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.001), LatLng(0.0, 0.002))
        val pts = RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = 90.0)
        assertTrue("east point should read as forward when heading east", has(pts, right = 0f, forward = 111.3f, tol = 2f))
    }

    @Test
    fun rightHandTurnReadsAsPositiveRight() {
        // Heading east; the road bends south-east → that's to the rider's right.
        val route = listOf(LatLng(0.0, 0.0), LatLng(-0.0005, 0.001))
        val pts = RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = 90.0)
        assertTrue("a south-east bend while heading east must project to the right", pts.any { it.rightM > 20f })
    }

    @Test
    fun missingHeadingOrTinyRouteProjectsEmpty() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0))
        assertTrue(RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = null).isEmpty())
        assertTrue(RouteTrajectory.project(listOf(LatLng(0.0, 0.0)), LatLng(0.0, 0.0), headingDeg = 0.0).isEmpty())
    }

    @Test
    fun longRouteIsDecimatedToThePointCap() {
        val route = (0..300).map { LatLng(it * 0.0001, 0.0) } // ~3.3 km due north, 301 points
        val pts = RouteTrajectory.project(route, LatLng(0.0, 0.0), headingDeg = 0.0)
        assertTrue("projected path must respect the point cap", pts.size <= RouteTrajectory.MAX_POINTS)
    }
}
