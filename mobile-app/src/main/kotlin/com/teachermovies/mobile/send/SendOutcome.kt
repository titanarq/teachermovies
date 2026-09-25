package com.teachermovies.mobile.send

/**
 * What sending a magnet to the TV came to (#198). [message] is the Spanish text the phone shows for
 * it, so no screen builds a string of its own. Only [NeedsPairing] also means the stored token is
 * gone, which takes the app back to pairing.
 */
sealed interface SendOutcome {
    /** The Spanish text shown to the user for this outcome. */
    val message: String

    /** The TV accepted the magnet, so its download starts. */
    data class Sent(
        val tvName: String,
    ) : SendOutcome {
        override val message: String
            get() = "Enviado a $tvName"
    }

    /** The TV already had this exact torrent. */
    data class AlreadyOnTv(
        val tvName: String,
    ) : SendOutcome {
        override val message: String
            get() = "Ya estaba en $tvName"
    }

    /** The TV answered `invalid_magnet`: not a link it can download. */
    data object Rejected : SendOutcome {
        override val message: String
            get() = "La TV rechazó el enlace magnet"
    }

    /** The typed or shared text holds no magnet link. */
    data object NoMagnet : SendOutcome {
        override val message: String
            get() = "No hay ningún enlace magnet"
    }

    /** No TV is paired yet. */
    data object NotPaired : SendOutcome {
        override val message: String
            get() = "Empareja primero la TV"
    }

    /** The TV refused the token (401), so it has to be paired again. */
    data object NeedsPairing : SendOutcome {
        override val message: String
            get() = "Vuelve a emparejar la TV"
    }

    /** The TV could not be reached, or answered with anything else than the documented outcomes. */
    data object Unreachable : SendOutcome {
        override val message: String
            get() = "No se puede conectar con la TV"
    }
}
