package com.marianocarrizo.dynamicMusic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Gestiona la persistencia de la sesión de usuario en disco.
 * Ruta de almacenamiento: %APPDATA%\DynamicMusic\session.json
 * (con fallback a user.home/AppData/Roaming si APPDATA no está definida).
 *
 * La escritura es atómica: primero escribe en un archivo .tmp y luego
 * lo renombra/mueve a session.json para evitar archivos corruptos si la
 * aplicación se cierra abruptamente durante el guardado.
 */
public class SessionConfigManager {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Test seam: when set, replaces the APPDATA-based session file location
    private final Path pathOverride;

    public SessionConfigManager() {
        this.pathOverride = null;
    }

    /** Test seam: uses {@code sessionFile} instead of %APPDATA%/DynamicMusic/session.json. */
    SessionConfigManager(Path sessionFile) {
        this.pathOverride = sessionFile;
    }

    /**
     * Resuelve la ruta canónica del archivo de sesión según las variables de entorno.
     */
    private Path configPath() {
        if (pathOverride != null) return pathOverride;
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            appdata = System.getProperty("user.home") + "/AppData/Roaming";
        }
        return Paths.get(appdata, "DynamicMusic", "session.json");
    }

    /**
     * Carga la configuración de sesión desde disco.
     * Retorna una configuración vacía (sin lanzar excepciones) si:
     *  - El archivo no existe
     *  - El JSON está malformado
     *  - Ocurre un error de E/S
     */
    public SessionConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            System.out.println("No session config found — starting fresh");
            SessionConfig fresh = new SessionConfig();
            fresh.getActiveScene(); // single empty "Default" scene
            return fresh;
        }
        try {
            SessionConfig cfg;
            try (Reader reader = Files.newBufferedReader(path)) {
                cfg = gson.fromJson(reader, SessionConfig.class);
            }
            if (cfg == null) cfg = new SessionConfig();
            // Legacy (pre-scenes) files are converted to one "Default" scene and saved right away.
            // Done after the reader is closed: Windows cannot replace a file that is still open.
            if (SceneMigration.migrate(cfg, path)) save(cfg);
            return cfg;
        } catch (Exception e) {
            System.err.println("Error al leer la sesión guardada: " + e.getMessage());
            SessionConfig fallback = new SessionConfig();
            fallback.getActiveScene();
            return fallback;
        }
    }

    /**
     * Guarda la configuración de sesión en disco de forma atómica.
     * Usa un archivo temporal + Files.move con ATOMIC_MOVE para garantizar
     * que session.json nunca quede en estado parcialmente escrito.
     * Si el filesystem no soporta ATOMIC_MOVE, usa REPLACE_EXISTING como fallback.
     */
    public void save(SessionConfig cfg) {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling("session.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                gson.toJson(cfg, writer);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Fallback si el filesystem no soporta operaciones atómicas
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Error al guardar la sesión: " + e.getMessage());
        }
    }
}
