package dev.burixon.mockgps

import android.content.Context
import dev.burixon.mockgps.core.ClientScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal data class IntegrationClient(val id: String, val name: String, val token: String,
    val scopes: Set<ClientScope>, val lastSequence: Long = -1, val lastEventTime: Long = -1)

/** Independent capabilities and replay counters. A failed commit closes access until process restart. */
internal object IntegrationClients {
    private var storageFailed = false
    private fun prefs(context: Context) = context.getSharedPreferences("integration_clients", Context.MODE_PRIVATE)
    fun all(context: Context): List<IntegrationClient> {
        check(!storageFailed)
        val array = JSONArray(prefs(context).getString("values", "[]"))
        require(array.length() <= 16)
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            val scopes = value.getJSONArray("scopes")
            IntegrationClient(value.getString("id"), value.getString("name"), value.getString("token"),
                (0 until scopes.length()).map { ClientScope.valueOf(scopes.getString(it)) }.toSet(),
                value.getLong("seq"), value.getLong("time"))
                .also { require(it.id.length == 36 && it.name.length in 1..80 && it.token.matches(Regex("[a-f0-9]{64}"))) }
        }
    }
    fun find(context: Context, token: String?): IntegrationClient? {
        if (token == null || !token.matches(Regex("[a-f0-9]{64}"))) return null
        return all(context).firstOrNull { MessageDigest.isEqual(it.token.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII)) }
    }
    fun add(context: Context, name: String, scopes: Set<ClientScope>): IntegrationClient {
        require(name.isNotBlank() && name.length <= 80 && scopes.isNotEmpty())
        val existing = all(context)
        require(existing.size < 16)
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        val client = IntegrationClient(UUID.randomUUID().toString(), name.trim(), token, scopes)
        write(context, existing + client)
        return client
    }
    fun remove(context: Context, id: String) = write(context, all(context).filterNot { it.id == id })
    fun consume(context: Context, client: IntegrationClient, sequence: Long, time: Long) =
        write(context, all(context).map { if (it.id == client.id) it.copy(lastSequence = sequence, lastEventTime = maxOf(time, it.lastEventTime)) else it })

    private fun write(context: Context, clients: List<IntegrationClient>) {
        val array = JSONArray()
        clients.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("token", it.token)
            .put("scopes", JSONArray(it.scopes.map { scope -> scope.name })).put("seq", it.lastSequence).put("time", it.lastEventTime)) }
        try { check(prefs(context).edit().putString("values", array.toString()).commit()) }
        catch (failure: RuntimeException) { storageFailed = true; throw failure }
    }
}
