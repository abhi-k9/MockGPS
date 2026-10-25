package dev.burixon.mockgps

import android.content.Intent
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.burixon.mockgps.core.ClientScope
import dev.burixon.mockgps.engine.Coordinates

/** Secondary local configuration screens; policy and Android effects remain in ControlCenter. */
internal class FeaturePanels(private val activity: AppCompatActivity, private val changed: () -> Unit) {
    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun guarded(action: () -> Unit) {
        try { action(); changed() } catch (_: RuntimeException) { message(activity.getString(R.string.local_storage_unavailable)) }
    }
    fun presets() = guarded {
        val presets = LocationPresets.all(activity)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.location_presets)
            .setItems(presets.map { it.name }.toTypedArray()) { _, index -> preset(presets[index]) }
            .setPositiveButton(R.string.save_selected_location) { _, _ -> addPreset() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun addPreset() {
        val latitude = State.getLat(activity)
        val longitude = State.getLon(activity)
        if (latitude == null || longitude == null) { message(activity.getString(R.string.no_location_selected)); return }
        val column = column()
        val name = EditText(activity).apply { hint = activity.getString(R.string.preset_name); setSingleLine(true) }
        val approval = CheckBox(activity).apply { setText(R.string.allow_preset_automation) }
        column.addView(name); column.addView(approval)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.save_selected_location).setView(column)
            .setPositiveButton(R.string.save, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = name.text.toString().trim()
                if (value.isBlank() || value.length > 80) name.error = activity.getString(R.string.invalid_name)
                else guarded { LocationPresets.save(activity, value, Coordinates(latitude, longitude), approval.isChecked); dialog.dismiss() }
            }
        }
        dialog.show()
    }
    private fun preset(value: LocationPreset) {
        val options = arrayOf(activity.getString(R.string.use_location), activity.getString(R.string.copy_preset_id),
            activity.getString(if (value.automationAllowed) R.string.disallow_preset_automation else R.string.allow_preset_automation),
            activity.getString(R.string.delete))
        MaterialAlertDialogBuilder(activity).setTitle(value.name)
            .setMessage(null as CharSequence?)
            .setItems(options) { _, index -> guarded {
                when (index) {
                    0 -> check(ControlCenter.chooseLocation(activity, value.point.latitude, value.point.longitude))
                    1 -> copySensitive("Preset ID", value.id)
                    2 -> LocationPresets.approve(activity, value.id, !value.automationAllowed)
                    3 -> LocationPresets.remove(activity, value.id)
                }
            } }.show()
    }

    fun clients() = guarded {
        val values = IntegrationClients.all(activity)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.integration_clients)
            .setItems((listOf(activity.getString(R.string.default_integration_access)) + values.map { it.name }).toTypedArray()) { _, index ->
                if (index == 0) defaultAccess() else client(values[index - 1])
            }.setPositiveButton(R.string.add_integration) { _, _ -> addClient() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun defaultAccess() {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.default_integration_access)
            .setView(android.widget.TextView(activity).apply { setText(R.string.preset_access_description); setPadding(48, 0, 48, 16) })
            .setMultiChoiceItems(arrayOf(activity.getString(R.string.allow_preset_selection)),
                booleanArrayOf(AutomationSettings.defaultPresetAccess(activity))) { _, _, checked -> guarded {
                    AutomationSettings.setDefaultPresetAccess(activity, checked)
                } }.setPositiveButton(android.R.string.ok, null).show()
    }
    private fun addClient() {
        val column = column()
        val name = EditText(activity).apply { hint = activity.getString(R.string.integration_name); setSingleLine(true) }
        column.addView(name)
        val scopes = ClientScope.entries
        val labels = listOf(R.string.scope_status, R.string.scope_readiness, R.string.scope_stop, R.string.scope_presets)
        val checks = scopes.mapIndexed { index, scope ->
            CheckBox(activity).apply { setText(labels[index]); isChecked = scope != ClientScope.SELECT_PRESET; column.addView(this) }
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.add_integration).setView(column)
            .setMessage(R.string.client_agreement_description)
            .setPositiveButton(R.string.create_credential, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                val selected = scopes.filterIndexed { index, _ -> checks[index].isChecked }.toSet()
                if (label.isBlank() || label.length > 80 || selected.isEmpty()) name.error = activity.getString(R.string.invalid_name_and_scope)
                else guarded {
                    ControlCenter.clientsChanged(activity)
                    val value = IntegrationClients.add(activity, label, selected)
                    ControlCenter.clientsChanged(activity)
                    dialog.dismiss(); client(value)
                }
            }
        }
        dialog.show()
    }
    private fun client(value: IntegrationClient) {
        MaterialAlertDialogBuilder(activity).setTitle(value.name)
            .setItems(arrayOf(activity.getString(R.string.reveal_credential), activity.getString(R.string.revoke_credential))) { _, index ->
                if (index == 0) {
                    val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.automation_credential)
                        .setMessage(activity.getString(R.string.credential_warning, value.token))
                        .setPositiveButton(R.string.copy_credential) { _, _ -> copySensitive("Integration credential", value.token) }
                        .setNegativeButton(android.R.string.cancel, null).create()
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.show()
                } else MaterialAlertDialogBuilder(activity).setTitle(R.string.revoke_credential)
                    .setMessage(R.string.revoke_client_confirmation)
                    .setPositiveButton(R.string.revoke_short) { _, _ -> guarded {
                        ControlCenter.clientsChanged(activity)
                        IntegrationClients.remove(activity, value.id)
                        ControlCenter.clientsChanged(activity)
                    } }.setNegativeButton(android.R.string.cancel, null).show()
            }.show()
    }
    fun sessionLimit() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AutomationSettings.maxSessionMinutes(activity).toString()); selectAll()
            contentDescription = activity.getString(R.string.session_limit_description)
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.session_limit).setMessage(R.string.session_limit_description)
            .setView(input).setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 0..1440) input.error = activity.getString(R.string.invalid_session_limit)
                else guarded {
                    // A changed ceiling applies to the next session; invalidate the current one first.
                    ControlCenter.forceReal(activity)
                    AutomationSettings.setMaxSessionMinutes(activity, minutes)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
    fun quietNotifications() {
        val column = column()
        val errors = CheckBox(activity).apply { setText(R.string.error_notifications); isChecked = AutomationSettings.errorAlerts(activity) }
        column.addView(errors)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.quiet_notifications).setMessage(R.string.quiet_notifications_description)
            .setView(column).setPositiveButton(R.string.use_quiet_profile) { _, _ -> guarded {
                AutomationSettings.setAlertMode(activity, AlertMode.TOAST)
                AutomationSettings.setErrorAlerts(activity, errors.isChecked)
            } }.setNeutralButton(R.string.save) { _, _ -> guarded { AutomationSettings.setErrorAlerts(activity, errors.isChecked) } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    fun diagnostics() {
        val s = ControlCenter.snapshot(activity)
        val command = ControlCenter.commandDiagnostic()
        val report = "MockGPS ${BuildConfig.VERSION_NAME}\nMode: ${s.mode}\nService: ${s.service}\nProviders: ${s.providers}\n" +
            "Injection: ${s.injection}\nReadiness: ${s.readiness}\nAutomation armed: ${MockLocationService.isAutomationArmed()}\n" +
            "Last accepted command: ${command.lastAccepted}\nLast rejection: ${command.lastRejected}\nTransition: ${s.result}\nTimed override: ${s.temporary?.kind ?: "NONE"}\n${ControlCenter.recoveryStatus()}\n\n${EventHistory.report()}"
        MaterialAlertDialogBuilder(activity).setTitle(R.string.diagnostics_preview).setMessage(report)
            .setPositiveButton(R.string.share_report) { _, _ ->
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, report), activity.getString(R.string.share_report)))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun copySensitive(label: String, value: String) = SensitiveClipboard.copy(activity, label, value)
    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
    }
}
