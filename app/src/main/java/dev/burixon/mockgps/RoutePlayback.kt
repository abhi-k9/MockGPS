package dev.burixon.mockgps

import android.content.Context
import dev.burixon.mockgps.core.RoutePlan
import dev.burixon.mockgps.core.RoutePoint
import dev.burixon.mockgps.engine.Coordinates
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local route data; file names, GPX metadata and content URIs are not retained. */
internal object RoutePlayback {
    private fun prefs(context: Context) = context.getSharedPreferences("route_playback", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun load(context: Context): RoutePlan? {
        val values = JSONArray(prefs(context).getString("points", "[]"))
        if (values.length() == 0) return null
        require(values.length() <= 2000)
        val points = (0 until values.length()).map {
            val point = values.getJSONArray(it)
            RoutePoint(Coordinates(point.getDouble(0), point.getDouble(1)), point.getDouble(2))
        }
        return RoutePlan(points, prefs(context).getFloat("speed", 1.4f).toDouble())
    }
    fun save(context: Context, points: List<RoutePoint>) {
        RoutePlan(points, 1.4)
        val array = JSONArray()
        points.forEach { array.put(JSONArray().put(it.point.latitude).put(it.point.longitude).put(it.altitude)) }
        check(prefs(context).edit().putString("points", array.toString()).putBoolean("enabled", false).commit())
    }
    fun configure(context: Context, enabled: Boolean, speed: Double = 1.4) {
        require(speed.isFinite() && speed in 0.1..100.0)
        if (enabled) { requireNotNull(load(context)); }
        check(prefs(context).edit().putBoolean("enabled", enabled).putFloat("speed", speed.toFloat()).commit())
    }
    fun clear(context: Context) { check(prefs(context).edit().clear().commit()) }
}
