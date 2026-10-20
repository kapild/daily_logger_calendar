package dev.kapil.healthcal

/**
 * Drops place signals that don't change where you are.
 *
 * The phone's geofencing sometimes re-reports every fence at once, for example
 * after the fences are re-armed: "Left home", "Left gym" and "Arrived office"
 * at the same minute while you have been at the office all day. Those carry no
 * news, so they are ignored:
 *  - a "left X" when the last thing known about X is that you left it;
 *  - an "arrived X" when nothing has happened since you last arrived at X
 *    (no car, shuttle, or other place in between).
 */
object SignalFilter {
    private val MOVEMENT = setOf(
        SignalType.ARRIVE, SignalType.LEAVE, SignalType.CAR_ON, SignalType.CAR_OFF,
        SignalType.SHUTTLE, SignalType.SHUTTLE_OFF,
    )

    /** Whether [s] adds nothing, given the last signal for its place and the last movement signal of any kind. */
    fun isNoOp(s: Signal, lastForPlace: Signal?, lastMovement: Signal?): Boolean = when (s.type) {
        SignalType.LEAVE -> lastForPlace?.type == SignalType.LEAVE
        SignalType.ARRIVE -> lastMovement != null && lastMovement.type == SignalType.ARRIVE && lastMovement.place == s.place
        else -> false
    }

    /** [signals] (oldest first) without the no-op place signals. */
    fun clean(signals: List<Signal>): List<Signal> {
        val out = ArrayList<Signal>(signals.size)
        val lastByPlace = HashMap<String, Signal>()
        var lastMovement: Signal? = null
        for (s in signals) {
            val place = s.place
            if (place != null && isNoOp(s, lastByPlace[place], lastMovement)) continue
            out += s
            if (place != null) lastByPlace[place] = s
            if (s.type in MOVEMENT) lastMovement = s
        }
        return out
    }

    fun isMovement(type: SignalType) = type in MOVEMENT
    val movementTypes: Array<SignalType> get() = MOVEMENT.toTypedArray()
}
