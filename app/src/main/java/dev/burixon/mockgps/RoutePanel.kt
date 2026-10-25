package dev.burixon.mockgps

import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.burixon.mockgps.core.GpxRoute
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

internal class RoutePanel(private val activity: AppCompatActivity, private val changed: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private var closed = false
    private var importing = false
    fun show(import: () -> Unit) {
        val route = runCatching { RoutePlayback.load(activity) }.getOrNull()
        val options = listOf(activity.getString(R.string.import_gpx)) + if (route != null) listOf(
            activity.getString(if (RoutePlayback.enabled(activity)) R.string.disable_route else R.string.enable_route),
            activity.getString(R.string.delete_route)) else emptyList()
        MaterialAlertDialogBuilder(activity).setTitle(R.string.route_playback)
            .setItems(options.toTypedArray()) { _, index ->
                when (index) {
                    0 -> if (!importing) import()
                    1 -> if (RoutePlayback.enabled(activity)) update {
                        ControlCenter.forceReal(activity); RoutePlayback.configure(activity, false)
                    } else configure()
                    2 -> update { ControlCenter.forceReal(activity); RoutePlayback.clear(activity) }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    fun importRoute(uri: Uri) {
        if (closed || importing) return
        importing = true
        Toast.makeText(activity, R.string.importing_route, Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching {
                val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(!Thread.currentThread().isInterrupted)
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= GpxRoute.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                GpxRoute.parse(text)
            }
            activity.runOnUiThread {
                importing = false
                if (closed || activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                result.fold(onSuccess = { points -> update {
                    ControlCenter.forceReal(activity)
                    RoutePlayback.save(activity, points)
                    Toast.makeText(activity, R.string.route_imported, Toast.LENGTH_LONG).show()
                } }, onFailure = { Toast.makeText(activity, R.string.invalid_route, Toast.LENGTH_LONG).show() })
            }
        }
    }
    private fun configure() {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.route_speed)
            contentDescription = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("5"); selectAll()
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.enable_route).setMessage(R.string.route_description)
            .setView(input).setPositiveButton(R.string.enable_route, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val speed = input.text.toString().toDoubleOrNull()
                if (speed == null || !speed.isFinite() || speed !in 0.36..360.0) input.error = activity.getString(R.string.invalid_route_speed)
                else update {
                    val route = requireNotNull(RoutePlayback.load(activity))
                    ControlCenter.forceReal(activity)
                    check(ControlCenter.chooseLocation(activity, route.points.first().point.latitude, route.points.first().point.longitude))
                    RoutePlayback.configure(activity, true, (speed / 3.6).coerceIn(0.1, 100.0))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    private fun update(action: () -> Unit) {
        runCatching { action(); changed() }.onFailure { Toast.makeText(activity, R.string.local_storage_unavailable, Toast.LENGTH_LONG).show() }
    }
    fun close() { closed = true; worker.shutdownNow() }
}
