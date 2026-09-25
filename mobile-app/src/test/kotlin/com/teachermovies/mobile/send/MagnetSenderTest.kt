package com.teachermovies.mobile.send

import com.teachermovies.mobile.api.AddMagnetOutcome
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.fake.FakeTvApi
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.fake.InMemoryPairedTvStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetSenderTest {
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"
    private val livingRoom = PairedTv("Movie Assistant (Salón)", "http://192.168.1.20:8787", "secret-token")

    private val api = FakeTvApi()

    private fun sender(store: InMemoryPairedTvStore = InMemoryPairedTvStore(livingRoom)) = MagnetSender(api, store)

    @Test
    fun `an accepted magnet reaches the paired TV with its base URL and token`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Added(id = "0123456789abcdef", state = "fetching_metadata")

            val outcome = sender().send(magnet)

            assertEquals(SendOutcome.Sent(livingRoom.instanceName), outcome)
            assertEquals(listOf(FakeTvApi.Call.AddMagnet(livingRoom.baseUrl, livingRoom.token, magnet)), api.calls)
        }

    @Test
    fun `only the magnet inside shared text is sent`() =
        runTest {
            sender().send("Mira esta peli $magnet\nQué buena pinta")

            assertEquals(listOf(FakeTvApi.Call.AddMagnet(livingRoom.baseUrl, livingRoom.token, magnet)), api.calls)
        }

    @Test
    fun `text without a magnet never reaches the API`() =
        runTest {
            val texts =
                listOf(
                    null,
                    "",
                    "   ",
                    "https://example.com/movie",
                    "magnet:?dn=Movie",
                    "MAGNET:?xt=urn:ed2k:0123456789abcdef",
                )

            for (text in texts) {
                assertEquals(text.toString(), SendOutcome.NoMagnet, sender().send(text))
            }
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `text without a magnet is refused before the paired TV is looked up`() =
        runTest {
            assertEquals(SendOutcome.NoMagnet, sender(InMemoryPairedTvStore()).send("no magnet here"))
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `no paired TV never reaches the API`() =
        runTest {
            assertEquals(SendOutcome.NotPaired, sender(InMemoryPairedTvStore()).send(magnet))
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `a torrent the TV already has is reported as such`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.AlreadyExists(id = "0123456789abcdef")

            assertEquals(SendOutcome.AlreadyOnTv(livingRoom.instanceName), sender().send(magnet))
        }

    @Test
    fun `a magnet the TV refuses is reported as rejected`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.InvalidMagnet

            assertEquals(SendOutcome.Rejected, sender().send(magnet))
        }

    @Test
    fun `a revoked token is forgotten and asks to pair again`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Failed(ApiFailure.Unauthorized)
            val store = InMemoryPairedTvStore(livingRoom)

            assertEquals(SendOutcome.NeedsPairing, sender(store).send(magnet))
            assertEquals(null, store.pairedTv.value)
        }

    @Test
    fun `any other failure is unreachable and keeps the paired TV`() =
        runTest {
            val failures =
                listOf(
                    ApiFailure.Network("timeout"),
                    ApiFailure.Http(status = 500, code = "engine_error", message = "nope"),
                    ApiFailure.Http(status = 503, code = null, message = null),
                )
            val store = InMemoryPairedTvStore(livingRoom)

            for (failure in failures) {
                api.addMagnetResult = AddMagnetOutcome.Failed(failure)
                assertEquals(failure.toString(), SendOutcome.Unreachable, sender(store).send(magnet))
            }
            assertEquals(livingRoom, store.pairedTv.value)
        }

    @Test
    fun `every outcome carries its Spanish message`() {
        val outcomes =
            listOf(
                SendOutcome.Sent("Salón") to "Enviado a Salón",
                SendOutcome.AlreadyOnTv("Salón") to "Ya estaba en Salón",
                SendOutcome.Rejected to "La TV rechazó el enlace magnet",
                SendOutcome.NoMagnet to "No hay ningún enlace magnet",
                SendOutcome.NotPaired to "Empareja primero la TV",
                SendOutcome.NeedsPairing to "Vuelve a emparejar la TV",
                SendOutcome.Unreachable to "No se puede conectar con la TV",
            )

        for ((outcome, message) in outcomes) {
            assertEquals(outcome.toString(), message, outcome.message)
        }
    }
}
