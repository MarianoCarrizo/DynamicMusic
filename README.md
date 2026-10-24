# DynamicMusic

Reproductor de música y ambientación para sesiones de rol de mesa. Cada **escena** agrupa cuatro moods (PAUSED, NORMAL, RAGED, ADVANTAGE) con su track, un fondo de imagen o video y un **aura** visual que reacciona a la música. Durante la partida se cambia de mood con el teclado y la música y el aura acompañan el momento.

Proyecto personal, sin fines comerciales.

## Funcionalidades

- **Escenas**: tracks de los cuatro moods, fondo (imagen o video con recorte A-B, velocidad y boomerang) y cinco auras: una por mood y una para la música on demand. Se guardan solas y se pueden duplicar con **Save as**.
- **Aura reactiva**: viñeta, brillo de borde, brasas y un pulso sutil del fondo. El centro de la pantalla queda limpio. El análisis separa la percusión del resto: el bombo marca el pulso, el redoblante los destellos y la parte tonal la respiración. PAUSED siempre se muestra calmo.
- **Live aura**: ajuste en vivo del preset, la intensidad y la reactividad del aura que está en pantalla.
- **Ventana de ambiance**: se muestra u oculta cuando se quiera y se puede enviar a otro monitor.
- **Play now (on demand)**: reproduce cualquier track de la biblioteca o de YouTube en el panel izquierdo, reemplazando al mood hasta que se elija uno.
- **Playlists**: reproducción en lista con shuffle y loop. Usan el aura on demand de la escena.
- **YouTube**: búsqueda dentro de la app y descarga del audio con una copia propia de yt-dlp que se actualiza sola.

## Requisitos

- **JDK 21+**
- **ffmpeg** en el PATH: lo usan la descarga de audio de YouTube y el boomerang de video.
- Opcional: **Node.js** o **Deno**. yt-dlp los usa para extraer de YouTube de forma confiable.

En Windows, yt-dlp no hace falta instalarlo: la app descarga su propia copia en la primera ejecución. En Linux y macOS hay que tener `yt-dlp` en el PATH.

## Correr la app

```bash
# Linux, macOS o Git Bash
./mvnw javafx:run
```

```powershell
# Windows (PowerShell o cmd)
.\mvnw.cmd javafx:run
```

### Modo debug

Habilita el HUD de diagnóstico del aura (tecla **F3** en la ventana de ambiance) y graba los valores del análisis de audio en `audio-debug.csv`:

```bash
./mvnw javafx:run -Pdebug
```

## Uso

| Acción | Cómo |
|---|---|
| Cargar la biblioteca | **Open Folder** |
| Asignar un track a un mood | Arrastrar el track a la tarjeta del mood, o clic derecho → **Assign to** |
| Cambiar de mood | Tarjetas `[1]`-`[4]` o teclas **1 / 2 / 3 / 4** |
| Escuchar un track antes de asignarlo | Doble clic en la biblioteca |
| Reproducir on demand | Clic derecho → **Play now**, o **Enter** sobre el track |
| YouTube | Pegar una URL y **Add** (a la playlist) o **Play** (on demand); **Browse** para buscar |
| Escenas | Selector arriba; **+** para crear, **Save as** para duplicar, **Edit scene** para configurar auras y fondo |
| Ajustar el aura en vivo | Franja **Live aura**, debajo de las pestañas |
| Ventana de ambiance | **Show/Hide ambiance**; la flecha del botón elige el monitor |

## Datos locales

Todo se guarda en `%APPDATA%\DynamicMusic` (en Linux y macOS, `~/DynamicMusic`):

| Ruta | Contenido |
|---|---|
| `session.json` | Escenas, playlists y preferencias. Al migrar una sesión antigua se guarda una copia en `session.json.bak` |
| `library/` | Copias permanentes de los tracks de YouTube usados en escenas |
| `yt-cache/` | Caché de descargas de YouTube (se limpia sola) |
| `bin/` | Copia de yt-dlp administrada por la app |

## Generar instalador para Windows (MSI)

Requiere WiX Toolset 3.x y los jmods de JavaFX 21 en `C:/javafx-jmods-21.0.2`.

```powershell
.\mvnw.cmd package -DskipTests
```

El instalador queda en `target/dist/DynamicMusic-1.0.0.msi`. El modo debug no se incluye en el instalador.

## Aviso sobre YouTube

La descarga de audio de YouTube es para uso personal. Respetar los derechos de autor y los términos de servicio de YouTube es responsabilidad de quien usa la app.

## Licencia

[Apache 2.0](LICENSE)
