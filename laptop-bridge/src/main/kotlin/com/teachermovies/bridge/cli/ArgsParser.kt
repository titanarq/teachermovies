package com.teachermovies.bridge.cli

import java.net.URI
import java.net.URISyntaxException

/**
 * Reads the command line of `teachermovies-bridge` (#271): an optional `--config <fichero>`
 * anywhere, then the subcommand and its own options.
 *
 * Everything a human can get wrong is settled here rather than in a command -- the URL's shape, a
 * `--level` the TV would refuse, a `--limit` that is not a number -- so a command always receives a
 * [Command] it can act on, and a mistake costs [ExitCode.USAGE] with a Spanish message and the
 * [USAGE] text instead of a pointless request to the TV. No message quotes a value: a value typed
 * after the wrong option could be the PIN.
 */
internal object ArgsParser {
    /** What `-h`, `--help`, `help` and any usage error print. */
    val USAGE: String =
        """
        teachermovies-bridge -- el puente del portátil con la TV (ADR-0005)

        Uso:
          teachermovies-bridge [--config <fichero>] <subcomando> [opciones]

        Subcomandos:
          pair             se empareja con la TV y guarda su URL y el token de ámbito 'bridge'
          unpair           olvida el emparejamiento guardado (borra el fichero de configuración)
          doctor           comprueba la configuración, sus permisos y la conexión con la TV
          logs             imprime una página del registro de la TV
          run              atiende los trabajos de la TV y se reconecta sola mientras el proceso viva
          install-service  escribe la unidad de usuario de systemd que mantiene 'run' en marcha

        Opciones de 'pair':
          --url <url>      URL de la TV, por ejemplo http://192.168.1.20:8787 (obligatoria)
          --pin <pin>      PIN que muestra la TV (obligatorio; nunca se imprime)
          --name <nombre>  nombre de este portátil (por defecto, el nombre del equipo)

        Opciones de 'logs':
          --since <n>      solo líneas con seq mayor que <n> (por defecto, todo el búfer)
          --level <nivel>  nivel mínimo: debug, info, warn o error (por defecto, todos)
          --limit <n>      tamaño de la página, 1 o mayor (por defecto, el de la TV)

        Opciones de 'install-service':
          --exec <ruta>    script 'teachermovies-bridge' que la unidad lanzará; '~' se expande y una
                           ruta relativa se resuelve contra el directorio de trabajo (por defecto,
                           el de la instalación que está en marcha)

        Opciones globales:
          --config <fichero>  otro fichero de configuración; '~' se expande y una ruta relativa
                              se resuelve contra el directorio de trabajo (por defecto
                              ~/.config/teachermovies-bridge/config.json)
          -h, --help, help    esta ayuda

        Códigos de salida: 0 correcto, 1 la operación falló, 2 uso incorrecto.
        El token de la TV se guarda en el fichero de configuración y no se imprime nunca; la unidad
        de systemd que escribe 'install-service' tampoco lo lleva.
        """.trimIndent()

    private const val CONFIG_OPTION = "--config"

    private val HELP_NAMES = setOf("-h", "--help", "help")

    private val PAIR_OPTIONS = setOf("--url", "--pin", "--name")

    private val LOGS_OPTIONS = setOf("--since", "--level", "--limit")

    private val INSTALL_SERVICE_OPTIONS = setOf("--exec")

    /** The levels `GET /api/logs` accepts, spelled the way the TV compares them (ADR-0006 §4). */
    private val LEVELS = listOf("debug", "info", "warn", "error")

    private val URL_SCHEMES = setOf("http", "https")

    fun parse(args: List<String>): ParseResult =
        try {
            parseOrThrow(args)
        } catch (e: UsageException) {
            ParseResult.UsageError(e.message)
        }

    private fun parseOrThrow(args: List<String>): ParseResult {
        if (args.isEmpty()) throw UsageException(null)
        var configSpec: String? = null
        val rest = mutableListOf<String>()
        var index = 0
        while (index < args.size) {
            if (args[index] != CONFIG_OPTION) {
                rest += args[index]
                index += 1
                continue
            }
            if (configSpec != null) throw UsageException("'$CONFIG_OPTION' solo puede darse una vez")
            configSpec = optionValue(args, index, CONFIG_OPTION)
            index += 2
        }
        if (rest.isEmpty()) throw UsageException(null)
        val name = rest[0]
        if (name in HELP_NAMES) return ParseResult.Help
        val command =
            when (name) {
                "pair" -> {
                    pairCommand(collectOptions(rest.drop(1), PAIR_OPTIONS))
                }

                "unpair" -> {
                    rejectOptions(rest.drop(1))
                    Command.Unpair
                }

                "doctor" -> {
                    rejectOptions(rest.drop(1))
                    Command.Doctor
                }

                "run" -> {
                    rejectOptions(rest.drop(1))
                    Command.Run
                }

                "logs" -> {
                    logsCommand(collectOptions(rest.drop(1), LOGS_OPTIONS))
                }

                "install-service" -> {
                    installServiceCommand(collectOptions(rest.drop(1), INSTALL_SERVICE_OPTIONS))
                }

                else -> {
                    throw UsageException("subcomando desconocido; '--help' los lista todos")
                }
            }
        return ParseResult.Parsed(configSpec = configSpec, command = command)
    }

    /** The `--name value` pairs in [args]; an unknown option or a loose argument is a usage error. */
    private fun collectOptions(
        args: List<String>,
        known: Set<String>,
    ): Map<String, String> {
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val option = args[index]
            if (!option.startsWith("--")) throw UsageException("se esperaba una opción, no un argumento suelto")
            if (option !in known) throw UsageException("opción desconocida: '$option'")
            values[option] = optionValue(args, index, option)
            index += 2
        }
        return values
    }

    private fun optionValue(
        args: List<String>,
        index: Int,
        option: String,
    ): String = args.getOrNull(index + 1) ?: throw UsageException("'$option' necesita un valor")

    private fun rejectOptions(args: List<String>) {
        if (args.isNotEmpty()) throw UsageException("este subcomando no admite opciones ni argumentos")
    }

    private fun pairCommand(values: Map<String, String>): Command.Pair =
        Command.Pair(
            url = httpUrl(required(values, "--url")),
            pin = required(values, "--pin"),
            deviceName = values["--name"]?.takeIf { it.isNotBlank() },
        )

    private fun logsCommand(values: Map<String, String>): Command.Logs =
        Command.Logs(
            since = values["--since"]?.let { cursor(it) },
            level = values["--level"]?.let { logLevel(it) },
            limit = values["--limit"]?.let { pageSize(it) },
        )

    /** `--exec` is the one option `install-service` takes; blank means "derive it", as absent does. */
    private fun installServiceCommand(values: Map<String, String>): Command.InstallService =
        Command.InstallService(exec = values["--exec"]?.takeIf { it.isNotBlank() })

    private fun required(
        values: Map<String, String>,
        option: String,
    ): String = values[option]?.takeIf { it.isNotBlank() } ?: throw UsageException("'$option' es obligatoria")

    /** `--since`: a `seq` cursor, 0 or greater (0 means "the whole buffer"). */
    private fun cursor(text: String): Long {
        val since = text.toLongOrNull() ?: throw UsageException("'--since' debe ser un número entero")
        if (since < 0L) throw UsageException("'--since' debe ser 0 o mayor")
        return since
    }

    /** `--limit`: a page size, 1 or greater; the TV caps it at its own ring buffer. */
    private fun pageSize(text: String): Int {
        val limit = text.toIntOrNull() ?: throw UsageException("'--limit' debe ser un número entero")
        if (limit < 1) throw UsageException("'--limit' debe ser 1 o mayor")
        return limit
    }

    private fun logLevel(text: String): String {
        val level = text.lowercase()
        if (level !in LEVELS) throw UsageException("'--level' debe ser uno de: ${LEVELS.joinToString(", ")}")
        return level
    }

    /** [text] with any trailing `/` removed, once it is an `http(s)` URL with a host to talk to. */
    private fun httpUrl(text: String): String {
        val uri =
            try {
                URI(text)
            } catch (e: URISyntaxException) {
                throw UsageException("'--url' no es una URL válida")
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in URL_SCHEMES || uri.host.isNullOrBlank()) {
            throw UsageException("'--url' debe ser una URL http:// o https:// con un nombre de equipo o una IP")
        }
        return text.trimEnd('/')
    }

    /** Signals a command line that cannot mean anything; [parse] turns it into a [ParseResult]. */
    private class UsageException(
        message: String?,
    ) : RuntimeException(message)
}
