# teachermovies — Visión del producto

Aplicación **centro multimedia autónomo en Android TV**, con su propio cliente BitTorrent y un pequeño servidor web local. Orientado siempre a contenido cuya descarga y reproducción estén autorizadas.

## Decisiones tecnológicas

- Android TV, Kotlin
- UI: Jetpack Compose for TV
- Torrent: jlibtorrent 2.x (libtorrent 2.x) — 2.0.12.9 soporta ARM/ARM64/x86/x86-64, BT v1, v2 e híbridos
- Player: libVLC Android (rama estable 3.7.1) — elegido sobre Media3 por soporte amplio de contenedores/códecs, múltiples pistas de audio y subtítulos en MKV impredecibles
- DB: Room · Configuración: DataStore
- HTTP local: servidor HTTP embebido (0.0.0.0:8787, puerto configurable) + WebSocket o SSE para progreso
- Discovery: Android NSD / mDNS (p.ej. `movieassistant.local`)
- TTS: Android TextToSpeech · IA: API intercambiable (NO en el camino crítico del MVP)

## Arquitectura

```
MÓVIL (navegador / futura app) → http://<ip-tv>:8787
ANDROID TV APP:
  Local HTTP Server (8787) → Torrent Manager (jlibtorrent)
     → Storage (SSD/USB/interna) + Torrent DB (Room)
     → Library → libVLC (audio EN, sub EN oculto)
     → Assistant Engine (EN → ES → TTS)
```

La descarga queda almacenada en la TV o, preferiblemente, en SSD/USB conectado. No se depende del móvil tras enviar el torrent.

## Fases / áreas

1. **Base Android TV.** Proyecto Kotlin, UI para mando (D-pad), configuración inicial, selección de almacenamiento, Room, pantalla principal con `Biblioteca`, `Descargas`, `Configuración`. Primera configuración muestra `Servidor: IP:8787`, espacio libre, carpeta de descarga, estado del motor torrent.
2. **Servidor local para el móvil.** HTTP ligero en `0.0.0.0:8787`. Web simple: `Pegar magnet`, `Subir .torrent`, `Subir subtítulo`, lista de descargas. WebSocket/SSE para progreso, velocidad, peers. La app configura el puerto, no la IP (DHCP; reserva DHCP en router recomendada). Mejora: anuncio mDNS/NSD.
3. **Cliente torrent real.** jlibtorrent como servicio independiente del reproductor. Recibe magnet o .torrent, obtiene metadata, muestra archivos, permite elegir qué descargar, persiste estado para continuar tras reinicio. Auto-prioridad 0 para samples/imágenes/extras; descargar solo película y .srt/.ass. Prioridades por archivo y pieza.
4. **Gestor de descargas.** Estados `Obteniendo metadata → Esperando → Descargando → Verificando → Completado → Error`. Pantalla: %, GB descargados, velocidad, peers, ETA, ratio, espacio libre. Resume data tras reinicio. Al completar aparece en `Biblioteca`.
5. **Almacenamiento.** Interna y USB/SSD (SSD casi requisito para 4K). Estructura `Movies/<torrent-id>/...`. Room guarda solo metadata: info-hash, nombre, ruta, progreso, archivo principal, pista de audio elegida, subtítulo elegido, último punto reproducido.
6. **Reproducción con libVLC.** Reproduce el fichero directamente; hardware decoding, subtítulos internos/externos, múltiples pistas de audio.
7. **Asistente de inglés.** Audio EN + subtítulo del asistente EN, con "Mostrar subtítulos: No". Subtitle Engine mantiene texto sincronizado. Botón del mando: pausa y captura `currentSubtitle`. Otro botón repite el fragmento original; otro TTS inglés; otro traducción castellana + TTS.
8. **Reproducir mientras descarga.** Después de que la descarga completa funcione, pero TorrentManager/TorrentEngine diseñado para ello desde el primer commit (priorización dinámica de piezas alrededor de la posición de reproducción + buffer).
9. **Seguridad del servidor local.** Primer inicio: TV genera token/PIN (se muestra `http://IP:8787` y `PIN 482916`). Móvil se empareja una vez y guarda token. Endpoints de añadir/borrar requieren `Authorization: Bearer`. Solo interfaces LAN, nada expuesto a Internet, sin UPnP para el HTTP.
10. **Segunda fase: app móvil.** Pequeña app Android que descubre la TV por NSD; `Compartir → Movie Assistant` envía magnet/enlace a la TV.

## API local

```
GET  /api/status
GET  /api/torrents
GET  /api/torrents/{id}
POST /api/torrents/magnet      {"magnet":"magnet:?xt=urn:btih:..."} → {"id":"95f8...","state":"fetching_metadata"}
POST /api/torrents/file
POST /api/torrents/{id}/pause
POST /api/torrents/{id}/resume
DELETE /api/torrents/{id}
GET  /api/torrents/{id}/files
PUT  /api/torrents/{id}/files
GET  /api/library
POST /api/subtitles
```

Ejemplo de estado: `{"name":"Movie","state":"downloading","progress":17.4,"downloadSpeed":8240000,"peers":34,"totalBytes":18123456789}`

## UX móvil (web)

```
MOVIE ASSISTANT
TV salón ● conectada
[ Pega aquí magnet o enlace ]
[ ENVIAR A LA TV ]   o   [ SUBIR .TORRENT ]
DESCARGANDO
Oppenheimer.mkv  ██████░░ 72 %  8.3 MB/s  18.4 / 25.6 GB  [ PAUSAR ]
```

## UX TV

```
BIBLIOTECA: tarjetas con película, 100 %, ▶
DESCARGAS: Movie B █████░░ 64 % 11.2 MB/s
```

## MVP 1.0 — recorrido crítico

Móvil → pego magnet → TV recibe → metadata → selecciona película → jlibtorrent descarga → almacenada → aparece en Biblioteca → libVLC reproduce.

Después: subtítulo EN oculto → botón del mando → "¿qué ha dicho?" → repetición original → TTS inglés → traducción ES.

Siguiente paso práctico: definir la estructura del proyecto Android (módulos, clases, interfaces, modelo de datos), separando `TorrentEngine` desde el primer commit.
