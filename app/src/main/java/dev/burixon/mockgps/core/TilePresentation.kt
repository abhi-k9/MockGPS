package dev.burixon.mockgps.core

/** The tile reports the chosen policy and the separate observation; it never starts injection. */
data class TilePresentation(
    val label: String,
    val status: String,
    val active: Boolean,
    val action: TileAction
) {
    val requiresUnlock: Boolean get() = action == TileAction.RETURN_AUTO

    companion object {
        fun from(snapshot: PolicySnapshot): TilePresentation = TilePresentation(
            label = snapshot.mode.name,
            status = snapshot.displayStatus(),
            active = snapshot.mode == OperatingMode.FORCE_REAL,
            action = if (snapshot.mode == OperatingMode.FORCE_REAL) TileAction.RETURN_AUTO else TileAction.FORCE_REAL
        )
    }
}

enum class TileAction { FORCE_REAL, RETURN_AUTO }
