package com.marianocarrizo.dynamicMusic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * POJO que representa una playlist de usuario.
 * Gson serializa los campos privados por reflexión; no se necesita TypeAdapter.
 * El getter getPaths() inicializa el campo de forma defensiva para que
 * una sesión.json sin el array "paths" nunca provoque NullPointerException.
 */
public class Playlist {

    private String name;
    private List<String> paths;

    /** Constructor vacío requerido por Gson. */
    public Playlist() {
        this.paths = new ArrayList<>();
    }

    /** Crea una playlist con el nombre dado y lista de rutas vacía. */
    public Playlist(String name) {
        this.name = name;
        this.paths = new ArrayList<>();
    }

    // --- Getters / setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Retorna la lista de rutas absolutas de las pistas.
     * Inicializa el campo de forma perezosa si Gson lo dejó null
     * (comportamiento defensivo idéntico al de SessionConfig.getAssignments).
     */
    public List<String> getPaths() {
        if (paths == null) paths = new ArrayList<>();
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    // --- Helpers de mutación ---

    /** Agrega una ruta al final de la playlist. */
    public void add(String path) {
        getPaths().add(path);
    }

    /**
     * Elimina la pista en la posición dada.
     * Es un no-op si el índice está fuera de rango.
     */
    public void removeAt(int index) {
        List<String> p = getPaths();
        if (index >= 0 && index < p.size()) {
            p.remove(index);
        }
    }

    /**
     * Mueve la pista de la posición {@code from} a la posición {@code to}.
     * Es un no-op si alguno de los índices está fuera de rango.
     */
    public void reorder(int from, int to) {
        List<String> p = getPaths();
        if (from < 0 || from >= p.size() || to < 0 || to >= p.size()) return;
        String item = p.remove(from);
        p.add(to, item);
    }

    /** Retorna la cantidad de pistas; 0 si el campo paths es null. */
    public int size() {
        return paths == null ? 0 : paths.size();
    }
}
