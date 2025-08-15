package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.utils.ConversorUtils;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class MusicService {


    private final Map<TrackType, MediaPlayer> players = new HashMap<>();
    private TrackType currentTrack = null;
    private Duration currentPosition = Duration.ZERO;


    public void loadTrack(TrackType type, File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Archivo no válido para " + type);
        }

        try {
            // Si es FLAC, convertir a WAV temporal
            if (file.getName().toLowerCase().endsWith(".flac")) {
                file = ConversorUtils.convertFlacToWav(file); // ahora devuelve archivo temporal
            }

            // Crear MediaPlayer
            Media media = new Media(file.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);

            // Nivelar volumen automáticamente según RMS
            double gain = ConversorUtils.calculateGainForRMS(file);
            player.setVolume(gain);

            player.setOnError(() -> System.err.println("Error en pista " + type + ": " + player.getError()));

            // Guardar player en el mapa
            players.put(type, player);

            // Si no hay pista actual, asignar esta
            if (currentTrack == null) {
                currentTrack = type;
            }

        } catch (Exception e) {
            System.err.println("No se pudo cargar la pista " + type + ": " + e.getMessage());
        }
    }




    /**
     * Cambia a otra versión manteniendo la posición actual.
     */
    public void switchTrack(TrackType newTrack) {
        if (!players.containsKey(newTrack)) return;

        // Guardar tiempo de la pista actual
        Duration currentTime = Duration.ZERO;
        if (currentTrack != null) {
            MediaPlayer currentPlayer = players.get(currentTrack);
            currentTime = currentPlayer.getCurrentTime();
            currentPlayer.pause();
        }

        currentTrack = newTrack;
        MediaPlayer nextPlayer = players.get(newTrack);

        // Mover al mismo tiempo que la pista anterior
        nextPlayer.seek(currentTime);
        nextPlayer.play();
    }

    /**
     * Reproduce la pista actual desde la posición guardada.
     */
    public void play() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).play();
        }
    }

    /**
     * Pausa la pista actual.
     */
    public void pause() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).pause();
        }
    }

    /**
     * Detiene y reinicia la posición a 0.
     */
    public void stop() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).stop();
            currentPosition = Duration.ZERO;
        }
    }

    /**
     * Devuelve si una pista está cargada.
     */
    public boolean isLoaded(TrackType type) {
        return players.containsKey(type);
    }

}
