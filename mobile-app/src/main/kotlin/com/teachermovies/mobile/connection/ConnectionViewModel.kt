package com.teachermovies.mobile.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.discovery.client.DiscoveredTv
import com.teachermovies.discovery.client.ServiceDiscoverer
import com.teachermovies.mobile.api.PairOutcome
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.PairedTvStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Finds the TV, pairs with it by PIN and remembers it (#197).
 *
 * Starts in [ConnectionUiState.Loading] until [store] gives its first value: a stored TV goes
 * straight to [ConnectionUiState.Connected], otherwise [ConnectionUiState.Searching]. From then on
 * [discoverer] is collected for the ViewModel's whole life: while searching it feeds the TV list,
 * and while connected a TV with the paired `instanceName` that resolves to another base URL (DHCP
 * moved it) is written back with [PairedTvStore.updateBaseUrl]. [store] is also collected for the
 * ViewModel's whole life: when it empties while [ConnectionUiState.Connected] (a revoked token was
 * cleared elsewhere), the state goes back to [ConnectionUiState.Searching]. [deviceName] is what
 * `POST /api/pair` receives. Nothing here logs the PIN or the token.
 */
class ConnectionViewModel(
    private val discoverer: ServiceDiscoverer,
    private val api: TvApi,
    private val store: PairedTvStore,
    private val deviceName: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Loading)

    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    // The last list discovery emitted, so returning to Searching shows it immediately.
    private var discovered: List<DiscoveredTv> = emptyList()

    private var pairingJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = store.pairedTv.first()
            _uiState.value =
                if (stored != null) ConnectionUiState.Connected(stored) else ConnectionUiState.Searching(discovered)
            launch { discoverer.discover().collect(::onDiscovered) }
            store.pairedTv.collect(::onStored)
        }
    }

    /** Chooses a discovered TV: asks for its PIN. */
    fun selectTv(tv: DiscoveredTv) {
        if (_uiState.value !is ConnectionUiState.Searching) return
        _uiState.value = ConnectionUiState.Pairing(instanceName = tv.instanceName, baseUrl = tv.baseUrl)
    }

    /**
     * Uses a typed `host` or `host:port` (default port [DEFAULT_PORT]) instead of a discovered TV.
     * An unparsable text only sets [ConnectionUiState.Searching.addressError].
     */
    fun enterAddress(text: String) {
        val searching = _uiState.value as? ConnectionUiState.Searching ?: return
        val address = parseAddress(text)
        if (address == null) {
            _uiState.value = searching.copy(addressError = INVALID_ADDRESS)
            return
        }
        _uiState.value =
            ConnectionUiState.Pairing(
                instanceName = "${address.host}:${address.port}",
                baseUrl = "http://${address.host}:${address.port}",
            )
    }

    /** Sends [pin] to the TV being paired; anything but exactly 6 digits never reaches the API. */
    fun submitPin(pin: String) {
        val pairing = _uiState.value as? ConnectionUiState.Pairing ?: return
        if (pairing.busy) return
        if (pin.length != PIN_LENGTH || !pin.all { it in '0'..'9' }) {
            _uiState.value = pairing.copy(error = PIN_FORMAT)
            return
        }
        val inFlight = pairing.copy(error = null, busy = true)
        _uiState.value = inFlight
        pairingJob =
            viewModelScope.launch {
                val outcome = api.pair(inFlight.baseUrl, pin, deviceName)
                // Cancelled (or otherwise moved on) while the request was out: drop the answer.
                if (_uiState.value != inFlight) return@launch
                _uiState.value =
                    when (outcome) {
                        is PairOutcome.Paired -> {
                            val tv = PairedTv(inFlight.instanceName, inFlight.baseUrl, outcome.token)
                            store.save(tv)
                            ConnectionUiState.Connected(tv)
                        }

                        PairOutcome.WrongPin -> {
                            inFlight.copy(busy = false, error = WRONG_PIN)
                        }

                        PairOutcome.TooManyAttempts -> {
                            inFlight.copy(busy = false, error = TOO_MANY_ATTEMPTS)
                        }

                        is PairOutcome.Failed -> {
                            inFlight.copy(busy = false, error = UNREACHABLE)
                        }
                    }
            }
    }

    /** Leaves the PIN screen for the TV list. */
    fun cancelPairing() {
        if (_uiState.value !is ConnectionUiState.Pairing) return
        pairingJob?.cancel()
        pairingJob = null
        _uiState.value = ConnectionUiState.Searching(discovered)
    }

    /** Forgets the paired TV and its token, and goes back to the TV list. */
    fun forgetTv() {
        if (_uiState.value !is ConnectionUiState.Connected) return
        viewModelScope.launch {
            store.clear()
            _uiState.value = ConnectionUiState.Searching(discovered)
        }
    }

    // The store emptied from elsewhere (a revoked token cleared by DownloadsViewModel or
    // MagnetSender): leave Connected for the TV list, as forgetTv() would.
    private fun onStored(tv: PairedTv?) {
        if (tv == null && _uiState.value is ConnectionUiState.Connected) {
            _uiState.value = ConnectionUiState.Searching(discovered)
        }
    }

    private suspend fun onDiscovered(tvs: List<DiscoveredTv>) {
        discovered = tvs
        when (val state = _uiState.value) {
            is ConnectionUiState.Searching -> {
                _uiState.value = state.copy(tvs = tvs)
            }

            is ConnectionUiState.Connected -> {
                val paired = state.pairedTv
                val moved = tvs.firstOrNull { it.instanceName == paired.instanceName } ?: return
                if (moved.baseUrl == paired.baseUrl) return
                store.updateBaseUrl(moved.baseUrl)
                val current = _uiState.value
                if (current is ConnectionUiState.Connected && current.pairedTv == paired) {
                    _uiState.value = ConnectionUiState.Connected(paired.copy(baseUrl = moved.baseUrl))
                }
            }

            ConnectionUiState.Loading, is ConnectionUiState.Pairing -> {
                Unit
            }
        }
    }

    /** Builds a [ConnectionViewModel] from the container's parts. */
    class Factory(
        private val discoverer: ServiceDiscoverer,
        private val api: TvApi,
        private val store: PairedTvStore,
        private val deviceName: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConnectionViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return ConnectionViewModel(discoverer, api, store, deviceName) as T
        }
    }

    private data class Address(
        val host: String,
        val port: Int,
    )

    companion object {
        /** The TV's HTTP port when a typed address has none (docs/modules/http-server.md). */
        const val DEFAULT_PORT = 8787

        const val INVALID_ADDRESS = "Dirección no válida"
        const val PIN_FORMAT = "El PIN tiene 6 cifras"
        const val WRONG_PIN = "PIN incorrecto"
        const val TOO_MANY_ATTEMPTS = "Demasiados intentos; espera un minuto"
        const val UNREACHABLE = "No se puede conectar con la TV"

        private const val PIN_LENGTH = 6
        private const val MAX_PORT = 65535

        // A host name or IPv4 literal: letters, digits, dots and hyphens (no scheme, path or space).
        private val HOST = Regex("[A-Za-z0-9.-]+")

        private fun parseAddress(text: String): Address? {
            val trimmed = text.trim()
            val parts = trimmed.split(':')
            val host = parts[0]
            if (!HOST.matches(host)) return null
            val port =
                when (parts.size) {
                    1 -> DEFAULT_PORT
                    2 -> parts[1].takeIf { p -> p.all { it in '0'..'9' } }?.toIntOrNull() ?: return null
                    else -> return null
                }
            if (port !in 1..MAX_PORT) return null
            return Address(host, port)
        }
    }
}
