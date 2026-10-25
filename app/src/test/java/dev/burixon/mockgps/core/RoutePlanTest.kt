package dev.burixon.mockgps.core

import dev.burixon.mockgps.engine.Coordinates
import org.junit.Assert.*
import org.junit.Test

class RoutePlanTest {
    @Test fun playbackMovesAndStopsWithoutLooping() {
        val route = RoutePlan(listOf(RoutePoint(Coordinates(0.0, 0.0), 10.0), RoutePoint(Coordinates(0.0, 0.001), 20.0)), 10.0)
        val frame = route.at(5000)
        assertTrue(frame.point.longitude in 0.0004..0.0005)
        assertTrue(frame.altitude in 14.0..15.0)
        assertEquals(90f, frame.bearing, 0.01f)
        assertFalse(frame.finished)
        val done = route.at(1_000_000)
        assertTrue(done.finished); assertEquals(0f, done.speed, 0f)
        assertEquals(0.001, done.point.longitude, 0.000001)
    }
    @Test fun dateLineCrossingUsesShortPath() {
        val route = RoutePlan(listOf(RoutePoint(Coordinates(0.0, 179.999)), RoutePoint(Coordinates(0.0, -179.999))), 10.0)
        assertTrue(route.distanceMeters in 220.0..225.0)
        assertTrue(kotlin.math.abs(route.at(11_000).point.longitude) > 179.999)
    }
    @Test fun validGpxIgnoresPrivateMetadataAndReadsAltitude() {
        val points = GpxRoute.parse("""<gpx xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>private</name></metadata><trk><trkseg><trkpt lat="0" lon="0"><ele>10</ele></trkpt><trkpt lat="0" lon="0.01"/></trkseg></trk></gpx>""")
        assertEquals(2, points.size); assertEquals(10.0, points.first().altitude, 0.0)
    }
    @Test fun malformedUnboundedAndEntityInputIsRejected() {
        val invalid = listOf(
            "<!DOCTYPE gpx SYSTEM 'https://example.invalid/file'><gpx/>",
            "<!DOCTYPE gpx [<!ENTITY x 'expansion'>]><gpx>&x;</gpx>",
            "<gpx><rte><rtept lat='NaN' lon='0'/><rtept lat='0' lon='1'/></rte></gpx>",
            "<gpx><trk><trkseg/><trkseg/></trk></gpx>",
            "<gpx>" + "<x>".repeat(40) + "</x>".repeat(40) + "</gpx>",
            "<gpx><rtept lat='0' lon='0'/><rtept lat='0' lon='1'/></gpx>",
            " ".repeat(GpxRoute.MAX_BYTES + 1), "<gpx/>")
        invalid.forEach { text -> assertTrue(runCatching { GpxRoute.parse(text) }.isFailure) }
    }
}
