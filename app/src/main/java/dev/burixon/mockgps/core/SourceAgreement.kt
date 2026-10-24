package dev.burixon.mockgps.core

/** Ephemeral source evidence: deliberately not restored across recovery. */
data class SourceAgreement(
    val externalEnabled: Boolean = false,
    val shortcutsEnabled: Boolean = false,
    val external: ReadinessState = ReadinessState.UNKNOWN,
    val shortcuts: ReadinessState = ReadinessState.UNKNOWN
) {
    val readiness: ReadinessState get() {
        val sources = listOfNotNull(external.takeIf { externalEnabled }, shortcuts.takeIf { shortcutsEnabled })
        return when {
            ReadinessState.BLOCKED in sources -> ReadinessState.BLOCKED
            sources.isEmpty() || ReadinessState.UNKNOWN in sources -> ReadinessState.UNKNOWN
            else -> ReadinessState.READY
        }
    }
    fun external(state: ReadinessState) = copy(external = state,
        shortcuts = if (state != ReadinessState.READY && shortcuts == ReadinessState.READY) ReadinessState.UNKNOWN else shortcuts)
    fun shortcuts(state: ReadinessState) = copy(shortcuts = state,
        external = if (state != ReadinessState.READY && external == ReadinessState.READY) ReadinessState.UNKNOWN else external)
    // Untimestamped shortcut request cannot erase a paused safety latch, even on its own source.
    fun simulationRequest() = shortcuts(if (shortcuts == ReadinessState.BLOCKED) ReadinessState.BLOCKED else ReadinessState.UNKNOWN)
}

/** Atomic private storage makes a published shortcut usable after process eviction. */
interface ShortcutAuthorityStore {
    fun read(): Map<String, String>
    fun write(commands: Map<String, String>)
}

private class MemoryShortcutAuthorityStore : ShortcutAuthorityStore {
    private var commands = emptyMap<String, String>()
    override fun read(): Map<String, String> = commands
    override fun write(commands: Map<String, String>) { this.commands = commands.toMap() }
}

/** Strict typed inputs and single-use authority, consumed durably before executing a command. */
class ShortcutAuthority(private val store: ShortcutAuthorityStore = MemoryShortcutAuthorityStore()) {
    fun publish(values: Map<String, String>) { store.write(values.toMap()) }
    fun consume(command: Any?, nonce: Any?): Boolean {
        val commands = store.read()
        if (command !is String || nonce !is String || commands[command] != nonce) return false
        store.write(commands - command)
        return true
    }
}
