package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.model.Playlist;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Gestiona el estado de las playlists y el orden de reproducción.
 * Es puro estado + lógica de índices; no contiene ningún MediaPlayer ni código de audio.
 * Todos sus métodos deben invocarse desde el hilo FX.
 */
public class PlaylistManager {

    // Lista compartida con SessionConfig en el momento de guardar
    private List<Playlist> playlists;

    // Playlist activa actualmente seleccionada; null si ninguna
    private Playlist activePlaylist;

    // Puntero dentro del buffer de orden (0..order.length-1)
    private int position;

    private boolean shuffle;
    private boolean loop;

    // Buffer de reproducción: permutación de índices [0..n-1] de activePlaylist.paths
    private int[] order;

    private final Random random = new Random();

    public PlaylistManager() {
        this.playlists = new ArrayList<>();
        this.order = new int[0];
        this.position = 0;
    }

    // =========================================================================
    // T-03: CRUD de playlists + selección activa
    // =========================================================================

    /**
     * Crea una playlist nueva con el nombre dado (trimmed).
     * Rechaza nombres en blanco o duplicados (case-insensitive).
     * Retorna la instancia creada, o null si fue rechazada.
     */
    public Playlist createPlaylist(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) return null;
        final String finalName = name;
        boolean duplicate = playlists.stream()
                .anyMatch(p -> p.getName() != null && p.getName().equalsIgnoreCase(finalName));
        if (duplicate) return null;
        Playlist playlist = new Playlist(finalName);
        playlists.add(playlist);
        return playlist;
    }

    /**
     * Elimina la playlist con el nombre dado.
     * Si era la playlist activa, limpia el estado activo y el buffer.
     * Retorna true si fue encontrada y eliminada.
     */
    public boolean deletePlaylist(String name) {
        if (name == null) return false;
        boolean removed = playlists.removeIf(
                p -> p.getName() != null && p.getName().equalsIgnoreCase(name));
        if (removed && activePlaylist != null
                && activePlaylist.getName() != null
                && activePlaylist.getName().equalsIgnoreCase(name)) {
            activePlaylist = null;
            order = new int[0];
            position = 0;
        }
        return removed;
    }

    /**
     * Renombra una playlist.
     * Rechaza el nuevo nombre si está en blanco o ya existe (excluyendo la propia).
     * Retorna true si el renombrado fue exitoso.
     */
    public boolean renamePlaylist(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        newName = newName.trim();
        if (newName.isEmpty()) return false;
        final String finalNewName = newName;
        boolean duplicate = playlists.stream()
                .anyMatch(p -> p.getName() != null
                        && !p.getName().equalsIgnoreCase(oldName)
                        && p.getName().equalsIgnoreCase(finalNewName));
        if (duplicate) return false;
        Optional<Playlist> found = findByName(oldName);
        if (found.isEmpty()) return false;
        found.get().setName(finalNewName);
        return true;
    }

    /**
     * Establece la playlist activa por nombre.
     * Si no se encuentra, deja el estado activo en null.
     * Siempre reinicia position=0 y reconstruye el buffer de orden.
     */
    public void setActivePlaylist(String name) {
        if (name == null) {
            activePlaylist = null;
            order = new int[0];
            position = 0;
            return;
        }
        Optional<Playlist> found = findByName(name);
        if (found.isEmpty()) {
            activePlaylist = null;
            order = new int[0];
            position = 0;
            return;
        }
        activePlaylist = found.get();
        position = 0;
        rebuildOrder();
    }

    /**
     * Reemplaza la lista interna de playlists (usada al restaurar sesión).
     * No llama a setActivePlaylist; el llamador lo hace después.
     */
    public void setPlaylists(List<Playlist> loaded) {
        this.playlists = (loaded != null) ? loaded : new ArrayList<>();
    }

    /** Retorna la referencia viva a la lista de playlists (compartida con SessionConfig). */
    public List<Playlist> getPlaylists() {
        return playlists;
    }

    /** Busca una playlist por nombre (case-insensitive). */
    public Optional<Playlist> findByName(String name) {
        if (name == null) return Optional.empty();
        return playlists.stream()
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /** Retorna el nombre de la playlist activa, o null si no hay ninguna. */
    public String getActivePlaylistName() {
        return activePlaylist != null ? activePlaylist.getName() : null;
    }

    // =========================================================================
    // T-04: Edición de pistas con reconstrucción de orden
    // =========================================================================

    /**
     * Agrega una pista (ruta absoluta) a la playlist indicada.
     * Si es la playlist activa, reconstruye el buffer de orden.
     */
    public void addTrack(String playlistName, File file) {
        Optional<Playlist> found = findByName(playlistName);
        if (found.isEmpty()) return;
        found.get().add(file.getAbsolutePath());
        if (isActivePlaylist(playlistName)) {
            rebuildOrder();
        }
    }

    /**
     * Elimina la pista en el índice dado de la playlist indicada.
     * Si es la playlist activa, reconstruye el buffer y ajusta la posición.
     */
    public void removeTrack(String playlistName, int index) {
        Optional<Playlist> found = findByName(playlistName);
        if (found.isEmpty()) return;
        found.get().removeAt(index);
        if (isActivePlaylist(playlistName)) {
            rebuildOrder();
            clampPosition();
        }
    }

    /**
     * Reordena la pista en la posición activa.
     * Si es la playlist activa, reconstruye el buffer y ajusta la posición.
     */
    public void reorderTrack(String playlistName, int from, int to) {
        Optional<Playlist> found = findByName(playlistName);
        if (found.isEmpty()) return;
        found.get().reorder(from, to);
        if (isActivePlaylist(playlistName)) {
            rebuildOrder();
            clampPosition();
        }
    }

    /** Ajusta position para que nunca quede fuera del rango del buffer. */
    private void clampPosition() {
        if (order.length == 0) {
            position = 0;
        } else {
            position = Math.max(0, Math.min(position, order.length - 1));
        }
    }

    /**
     * Reconstruye el buffer de orden según el estado de shuffle.
     * Shuffle OFF → orden identidad; Shuffle ON → Fisher-Yates.
     */
    private void rebuildOrder() {
        if (activePlaylist == null || activePlaylist.size() == 0) {
            order = new int[0];
            position = 0;
            return;
        }
        if (shuffle) {
            buildShuffledOrder();
        } else {
            int n = activePlaylist.size();
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
        }
        clampPosition();
    }

    private boolean isActivePlaylist(String name) {
        return activePlaylist != null
                && activePlaylist.getName() != null
                && activePlaylist.getName().equalsIgnoreCase(name);
    }

    // =========================================================================
    // T-05: Buffer de shuffle + navegación
    // =========================================================================

    /**
     * Construye una permutación Fisher-Yates del buffer, anclando la pista actual
     * (índice natural) en position=0 para que el track actual no cambie al activar shuffle.
     */
    private void buildShuffledOrder() {
        int n = activePlaylist.size();
        if (n == 0) {
            order = new int[0];
            position = 0;
            return;
        }
        // Índice natural de la pista actualmente en reproducción
        int currentNaturalIndex = (order.length > 0 && position < order.length)
                ? order[position] : 0;

        int[] newOrder = new int[n];
        for (int i = 0; i < n; i++) newOrder[i] = i;

        // Fisher-Yates
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = newOrder[i];
            newOrder[i] = newOrder[j];
            newOrder[j] = tmp;
        }

        // Intercambiar el elemento que vale currentNaturalIndex a la posición 0
        // para que la pista actual siga siendo la primera al activar shuffle mid-play
        for (int i = 0; i < n; i++) {
            if (newOrder[i] == currentNaturalIndex) {
                newOrder[i] = newOrder[0];
                newOrder[0] = currentNaturalIndex;
                break;
            }
        }

        order = newOrder;
        position = 0;
    }

    /**
     * Retorna la pista en la posición actual del buffer, o vacío si no hay playlist activa.
     */
    public Optional<File> getCurrentTrack() {
        if (activePlaylist == null || order.length == 0) return Optional.empty();
        List<String> paths = activePlaylist.getPaths();
        if (paths.isEmpty()) return Optional.empty();
        int idx = order[position];
        if (idx < 0 || idx >= paths.size()) return Optional.empty();
        return Optional.of(new File(paths.get(idx)));
    }

    /**
     * Avanza al siguiente track en el buffer de orden.
     * Comportamiento al llegar al final:
     *  - Loop ON  → reinicia (y baraja de nuevo si shuffle está activo); retorna el primer track.
     *  - Loop OFF → retorna Optional.empty() (señal de STOP para el llamador).
     */
    public Optional<File> next() {
        if (activePlaylist == null || order.length == 0) return Optional.empty();
        if (position < order.length - 1) {
            position++;
            return getCurrentTrack();
        }
        // Fin de la playlist
        if (loop) {
            if (shuffle) {
                buildShuffledOrder(); // reinicia barajado; position ya queda en 0
            } else {
                int n = activePlaylist.size();
                order = new int[n];
                for (int i = 0; i < n; i++) order[i] = i;
                position = 0;
            }
            return getCurrentTrack();
        }
        return Optional.empty(); // señal de STOP
    }

    /**
     * Retrocede al track anterior en el buffer de orden.
     * En position=0 es un no-op (no hace wrap); retorna el track actual.
     */
    public Optional<File> prev() {
        if (position > 0) {
            position--;
        }
        return getCurrentTrack();
    }

    /**
     * Salta directamente a la pista cuyo índice natural en la playlist es {@code uiIndex}.
     * Busca linealmente en el buffer; si no se encuentra, clampea el índice.
     */
    public Optional<File> startAt(int uiIndex) {
        if (activePlaylist == null || order.length == 0) return Optional.empty();
        for (int i = 0; i < order.length; i++) {
            if (order[i] == uiIndex) {
                position = i;
                return getCurrentTrack();
            }
        }
        position = Math.max(0, Math.min(uiIndex, order.length - 1));
        return getCurrentTrack();
    }

    /**
     * Activa o desactiva el modo shuffle.
     * Al activar: construye permutación Fisher-Yates anclando la pista actual.
     * Al desactivar: restaura orden identidad con position igual al índice natural actual.
     */
    public void setShuffle(boolean on) {
        this.shuffle = on;
        if (on) {
            buildShuffledOrder();
        } else {
            if (activePlaylist != null && activePlaylist.size() > 0) {
                int currentNaturalIdx = (order.length > 0 && position < order.length)
                        ? order[position] : 0;
                int n = activePlaylist.size();
                order = new int[n];
                for (int i = 0; i < n; i++) order[i] = i;
                position = Math.max(0, Math.min(currentNaturalIdx, n - 1));
            }
        }
    }

    /** Activa o desactiva el modo loop. */
    public void setLoop(boolean on) {
        this.loop = on;
    }

    public boolean isShuffle() { return shuffle; }
    public boolean isLoop()    { return loop; }

    /** Cantidad de pistas de la playlist activa; 0 si no hay ninguna seleccionada. */
    public int size() {
        return activePlaylist == null ? 0 : activePlaylist.size();
    }

    /**
     * Retorna la lista de rutas absolutas de la playlist activa,
     * o una lista vacía si no hay ninguna seleccionada.
     */
    public List<String> getActivePaths() {
        if (activePlaylist == null) return new ArrayList<>();
        return activePlaylist.getPaths();
    }

    /**
     * Índice natural (en la playlist) de la pista actualmente en reproducción.
     * Usado por el ListView para resaltar la fila correcta.
     */
    public int currentDisplayIndex() {
        return (order.length == 0) ? 0 : order[position];
    }

    /**
     * Etiqueta de posición en formato "N / T" (base 1), por ejemplo "2 / 5".
     * Retorna "— / —" si no hay playlist activa o está vacía.
     */
    public String positionLabel() {
        if (size() == 0) return "— / —";
        return (currentDisplayIndex() + 1) + " / " + size();
    }
}
