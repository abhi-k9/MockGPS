package dev.burixon.mockgps.core

import dev.burixon.mockgps.engine.Coordinates
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import kotlin.math.*

data class RoutePoint(val point: Coordinates, val altitude: Double = 0.0)
data class RouteFrame(val point: Coordinates, val altitude: Double, val speed: Float, val bearing: Float, val finished: Boolean)

/** Constant-speed great-circle playback with monotonic progress and no looping or teleport on completion. */
class RoutePlan(points: List<RoutePoint>, val speedMetersPerSecond: Double) {
    val points: List<RoutePoint> = points.toList()
    private val cumulative = DoubleArray(points.size)
    val distanceMeters: Double get() = cumulative.last()
    init {
        require(points.size in 2..2000 && speedMetersPerSecond.isFinite() && speedMetersPerSecond in 0.1..100.0)
        require(points.all { it.point.valid && it.altitude.isFinite() && it.altitude in -12_000.0..100_000.0 })
        for (index in 1 until points.size) cumulative[index] = cumulative[index - 1] + distance(points[index - 1].point, points[index].point)
        require(distanceMeters in 1.0..20_000_000.0)
    }
    fun at(elapsedMillis: Long): RouteFrame {
        val travelled = (elapsedMillis.coerceAtLeast(0) / 1000.0 * speedMetersPerSecond).coerceAtMost(distanceMeters)
        val end = cumulative.indexOfFirst { it > travelled }.let { if (it < 1) points.lastIndex else it }
        val start = end - 1
        val length = cumulative[end] - cumulative[start]
        val fraction = if (length < 0.001) 1.0 else ((travelled - cumulative[start]) / length).coerceIn(0.0, 1.0)
        val a = points[start]; val b = points[end]
        val lat1 = Math.toRadians(a.point.latitude); val lon1 = Math.toRadians(a.point.longitude)
        val lat2 = Math.toRadians(b.point.latitude); val lon2 = Math.toRadians(b.point.longitude)
        val angular = length / 6_371_000.0
        val divisor = sin(angular)
        val point = if (abs(divisor) < 1e-10) b.point else {
            val first = sin((1 - fraction) * angular) / divisor
            val second = sin(fraction * angular) / divisor
            val x = first * cos(lat1) * cos(lon1) + second * cos(lat2) * cos(lon2)
            val y = first * cos(lat1) * sin(lon1) + second * cos(lat2) * sin(lon2)
            val z = first * sin(lat1) + second * sin(lat2)
            Coordinates(Math.toDegrees(atan2(z, sqrt(x*x + y*y))), Math.toDegrees(atan2(y, x)))
        }
        val heading = (Math.toDegrees(atan2(sin(lon2-lon1)*cos(lat2), cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(lon2-lon1))) + 360) % 360
        val finished = travelled >= distanceMeters
        return RouteFrame(point, a.altitude + (b.altitude-a.altitude)*fraction,
            if (finished) 0f else speedMetersPerSecond.toFloat(), heading.toFloat() % 360f, finished)
    }
    private fun distance(a: Coordinates, b: Coordinates): Double {
        val dLat = Math.toRadians(b.latitude-a.latitude); val dLon = Math.toRadians(b.longitude-a.longitude)
        val h = sin(dLat/2).pow(2) + cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(dLon/2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}

object GpxRoute {
    const val MAX_BYTES = 1_048_576
    /** Call with bounded UTF-8 text. DTDs/entities are rejected before XML parsing. No URI resolution. */
    fun parse(text: String): List<RoutePoint> {
        require(text.length <= MAX_BYTES && '\u0000' !in text)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text))
        val points = mutableListOf<RoutePoint>()
        val stack = ArrayDeque<String>()
        var paths = 0
        var elements = 0
        var current: Coordinates? = null
        var altitude = 0.0
        val elevation = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entities forbidden")
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes) {
                val tag = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()
                require(++elements <= 12_000 && stack.size < 32)
                if (stack.isEmpty()) require(tag == "gpx")
                if (tag == "trkseg" || tag == "rte") require(++paths == 1)
                if (tag == "trkpt" || tag == "rtept") {
                    require((tag == "trkpt" && stack.lastOrNull() == "trkseg") || (tag == "rtept" && stack.lastOrNull() == "rte"))
                    require(points.size < 2000 && current == null)
                    current = Coordinates(attributes.getValue("lat").toDouble(), attributes.getValue("lon").toDouble()).also { require(it.valid) }
                    altitude = 0.0
                }
                if (tag == "ele") elevation.clear()
                stack.addLast(tag)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (stack.lastOrNull() == "ele" && current != null) { require(elevation.length + length <= 80); elevation.append(ch, start, length) }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (stack.removeLast()) {
                    "ele" -> if (current != null) altitude = elevation.toString().trim().toDouble().also { require(it.isFinite() && it in -12_000.0..100_000.0) }
                    "trkpt", "rtept" -> { points.add(RoutePoint(requireNotNull(current), altitude)); current = null }
                }
            }
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        val reader = factory.newSAXParser().xmlReader
        // Implementations differ on Android; the lexical ban and rejecting resolver also apply.
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { feature -> runCatching { reader.setFeature(feature, false) } }
        reader.entityResolver = handler; reader.contentHandler = handler; reader.errorHandler = handler
        reader.parse(InputSource(StringReader(text)))
        RoutePlan(points, 1.0) // Validate the whole route before it can enter storage.
        return points.toList()
    }
}
