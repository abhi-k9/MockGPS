package dev.burixon.mockgps

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.content.edit
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Map rendering and camera persistence. Location permissions and policy stay with the host. */
internal class LocationMap(
    private val activity: MainActivity,
    container: FrameLayout,
    savedState: Bundle?,
    private val select: (Double, Double) -> Unit,
    private val ready: (Boolean) -> Unit,
) {
    private val preferences = activity.getSharedPreferences("map_camera", android.content.Context.MODE_PRIVATE)
    private var map: MapLibreMap? = null
    private var selectedSource: GeoJsonSource? = null
    private var genuineSource: GeoJsonSource? = null
    private var genuine: Pair<Double, Double>? = null
    val view: MapView

    init {
        MapLibre.getInstance(activity)
        view = MapView(activity, MapLibreMapOptions.createFromAttributes(activity).textureMode(true))
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.onCreate(savedState)
        view.addOnDidFailLoadingMapListener { ready(false) }
        view.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isLogoEnabled = false
            loaded.uiSettings.isAttributionEnabled = true
            loaded.uiSettings.setCompassMargins(16.dp, 16.dp, 16.dp, 16.dp)
            loaded.addOnMapClickListener { point -> select(point.latitude, point.longitude); true }
            loaded.addOnCameraIdleListener { saveCamera() }
            loadStyle()
        }
    }

    fun retry() = loadStyle()

    private fun loadStyle() {
        map?.setStyle("https://tiles.openfreemap.org/styles/liberty") { style ->
            selectedSource = GeoJsonSource("selected-location")
            genuineSource = GeoJsonSource("genuine-location")
            style.addSource(genuineSource!!)
            style.addSource(selectedSource!!)
            style.addLayer(CircleLayer("genuine-location-marker", "genuine-location").withProperties(
                circleRadius(6f), circleColor(Color.rgb(90, 100, 110)),
                circleStrokeWidth(2f), circleStrokeColor(Color.WHITE),
            ))
            style.addLayer(CircleLayer("selected-location-marker", "selected-location").withProperties(
                circleRadius(10f), circleStrokeWidth(3f), circleStrokeColor(Color.WHITE),
            ))
            val restored = restoreCamera()
            updateSelection()
            genuine?.let { genuineSource?.setGeoJson(Point.fromLngLat(it.second, it.first)) }
            if (!restored) centerSelection()
            ready(true)
        }
    }

    fun updateSelection() {
        val lat = State.getLat(activity) ?: return
        val lon = State.getLon(activity) ?: return
        selectedSource?.setGeoJson(Point.fromLngLat(lon, lat))
        val color = if (State.isServiceRunning(activity)) R.color.simulation_stop else R.color.primary
        map?.style?.getLayerAs<CircleLayer>("selected-location-marker")
            ?.setProperties(circleColor(activity.getColor(color)))
    }

    fun showGenuine(lat: Double, lon: Double) {
        genuine = lat to lon
        genuineSource?.setGeoJson(Point.fromLngLat(lon, lat))
    }

    fun clearGenuine() {
        genuine = null
        genuineSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    fun centerSelection() {
        val lat = State.getLat(activity) ?: return
        val lon = State.getLon(activity) ?: return
        val loaded = map ?: return
        loaded.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), loaded.cameraPosition.zoom.coerceAtLeast(14.0)))
    }

    fun saveCamera() {
        val camera = map?.cameraPosition ?: return
        val target = camera.target ?: return
        preferences.edit {
            putLong("lat", target.latitude.toBits())
            putLong("lon", target.longitude.toBits())
            putLong("zoom", camera.zoom.toBits())
            putLong("bearing", camera.bearing.toBits())
            putLong("tilt", camera.tilt.toBits())
        }
    }

    private fun restoreCamera(): Boolean {
        if (!preferences.contains("lat") || !preferences.contains("lon") || !preferences.contains("zoom")) return false
        val lat = Double.fromBits(preferences.getLong("lat", 0))
        val lon = Double.fromBits(preferences.getLong("lon", 0))
        val zoom = Double.fromBits(preferences.getLong("zoom", 0))
        val bearing = Double.fromBits(preferences.getLong("bearing", 0))
        val tilt = Double.fromBits(preferences.getLong("tilt", 0))
        if (!State.validLat(lat) || !State.validLon(lon) || !zoom.isFinite() ||
            !bearing.isFinite() || !tilt.isFinite() || zoom !in 0.0..22.0 || tilt !in 0.0..60.0) return false
        map?.cameraPosition = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom)
            .bearing(bearing).tilt(tilt).build()
        return true
    }

    private val Int.dp: Int get() = (this * activity.resources.displayMetrics.density).toInt()
}
