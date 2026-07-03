package com.marianocarrizo.dynamicMusic.config;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POJO que representa la sesión guardada del usuario.
 * Almacena la ruta absoluta original de cada pista asignada por TrackType.
 * Se usa la ruta original (no el WAV temporal de FLAC) para que al recargar
 * se repita la conversión de forma consistente.
 *
 * Gson serializa el campo 'assignments' como Map<String,String> para evitar
 * problemas con EnumMap durante la deserialización.
 */
public class SessionConfig {

    // Clave = nombre del enum (ej. "PAUSED"), valor = ruta absoluta del archivo
    private Map<String, String> assignments;

    public SessionConfig() {
        this.assignments = new LinkedHashMap<>();
    }

    /**
     * Retorna los assignments convertidos a TrackType como clave.
     * Las entradas con claves inválidas (enum desconocido) se ignoran silenciosamente.
     */
    public Map<TrackType, String> getAssignments() {
        Map<TrackType, String> result = new EnumMap<>(TrackType.class);
        if (assignments == null) return result;
        assignments.forEach((key, value) -> {
            try {
                result.put(TrackType.valueOf(key), value);
            } catch (IllegalArgumentException ignored) {
                // Clave de enum desconocida: se ignora para mantener compatibilidad futura
            }
        });
        return result;
    }

    /**
     * Agrega o reemplaza la asignación de una pista.
     *
     * @param type         tipo de estado de ánimo
     * @param absolutePath ruta absoluta del archivo de audio original
     */
    public void put(TrackType type, String absolutePath) {
        if (assignments == null) {
            assignments = new LinkedHashMap<>();
        }
        assignments.put(type.name(), absolutePath);
    }
}
