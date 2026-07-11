package com.marianocarrizo.dynamicMusic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;

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

    /**
     * Resuelve la ruta canónica del archivo de sesión según las variables de entorno.
     */
    private Path configPath() {
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
            return new SessionConfig();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            SessionConfig cfg = gson.fromJson(reader, SessionConfig.class);
            return cfg != null ? cfg : new SessionConfig();
        } catch (Exception e) {
            System.err.println("Error al leer la sesión guardada: " + e.getMessage());
            return new SessionConfig();
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
