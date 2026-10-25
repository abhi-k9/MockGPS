package dev.burixon.mockgps

import android.content.Context
import dev.burixon.mockgps.engine.Coordinates
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class LocationPreset(val id: String, val name: String, val point: Coordinates, val automationAllowed: Boolean)

/** Private user-selected locations. Automation access is opt-in for each individual preset. */
internal object LocationPresets {
    private var storageFailed = false
    private fun prefs(context: Context) = context.getSharedPreferences("location_presets", Context.MODE_PRIVATE)
    fun all(context: Context): List<LocationPreset> {
        check(!storageFailed)
        val values = JSONArray(prefs(context).getString("values", "[]"))
        require(values.length() <= 50)
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            LocationPreset(value.getString("id"), value.getString("name"),
                Coordinates(value.getDouble("lat"), value.getDouble("lon")), value.optBoolean("automation", false))
                .also { require(it.point.valid && it.name.length in 1..80 && it.id.length == 36) }
        }
    }
    fun save(context: Context, name: String, point: Coordinates, approved: Boolean): LocationPreset {
        require(name.isNotBlank() && name.length <= 80 && point.valid)
        val preset = LocationPreset(UUID.randomUUID().toString(), name.trim(), point, approved)
        val existing = all(context)
        require(existing.size < 50)
        write(context, existing + preset)
        return preset
    }
    fun remove(context: Context, id: String) = write(context, all(context).filterNot { it.id == id })
    fun approve(context: Context, id: String, allowed: Boolean) = write(context,
        all(context).map { if (it.id == id) it.copy(automationAllowed = allowed) else it })
    private fun write(context: Context, values: List<LocationPreset>) {
        val array = JSONArray()
        values.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("lat", it.point.latitude).put("lon", it.point.longitude).put("automation", it.automationAllowed)) }
        try { check(prefs(context).edit().putString("values", array.toString()).commit()) }
        catch (failure: RuntimeException) { storageFailed = true; throw failure }
    }
}
