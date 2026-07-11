# DynamicMusic

Reproductor de música de fondo para sesiones de D&D. Asignás tracks a 4 modos de ambientación (PAUSED / NORMAL / RAGED / ADVANTAGE) y switcheás entre ellos en vivo con el teclado.

## Requisitos

- JDK 21+

## Correr la app

```bash
# Windows
./mvnw.cmd javafx:run

# Linux / macOS
./mvnw javafx:run
```

## Uso

| Acción | Cómo |
|---|---|
| Cargar carpeta de música | Botón **Open Folder** |
| Asignar track a un mood | Seleccioná el track → elegí mood en el combo → **Assign** |
| Switchear mood | Botones `[1]`–`[4]` o teclas **1 / 2 / 3 / 4** |
| Preview de un track | Doble clic en la lista |
| Loop | Botón 🔁 en el panel de mood |

Los assignments se guardan automáticamente y se restauran al abrir la app.

## Generar instalador Windows (MSI)

Requiere WiX Toolset 3.x y JavaFX 21 jmods en `C:/javafx-jmods-21.0.2`.

```bash
./mvnw.cmd package -DskipTests
```

El instalador queda en `target/dist/DynamicMusic-1.0.0.msi`.
