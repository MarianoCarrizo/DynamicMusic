package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.utils.ConversorUtils;
import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class MusicService {

    // Estructura interna controlada por el propio servicio
    private final Map<TrackType, MediaPlayer> players;
    private TrackType currentTrack;
    private boolean loopEnabled;
    private BiConsumer<Duration, Duration> progressListener;

    // Constructor limpio: el servicio nace vacío y listo para recibir tracks
    public MusicService() {
        this.players = new HashMap<>();
        this.currentTrack = null;
        this.loopEnabled = false;
        this.progressListener = null;
    }

    /**
     * Carga un archivo de audio asíncronamente y lo asigna a un estado de intensidad.
     */
    public void loadTrack(TrackType type, File file) {
        if (file == null || !file.exists()) return;

        try {
            // Conversión de formato si es necesario
            final File finalFile;
            if (file.getName().toLowerCase().endsWith(".flac")) {
                finalFile = ConversorUtils.convertFlacToWav(file);
            } else {
                finalFile = file;
            }

            // Preparación del Media de JavaFX
            String sourceUrl = finalFile.toURI().toString();
            Media media = new Media(sourceUrl);
            MediaPlayer player = new MediaPlayer(media);

            // Normalización de volumen basada en RMS
            double gain = ConversorUtils.calculateGainForRMS(finalFile);
            player.setVolume(gain);

            // Manejo de errores nativos de JavaFX Media
            player.setOnError(() -> System.err.println("Error en pista " + type + ": " + player.getError()));

            // Comportamiento del bucle/loop
            player.setOnEndOfMedia(() -> {
                if (loopEnabled) {
                    player.seek(Duration.ZERO);
                    player.play();
                }
            });

            // Listener de progreso continuo
            player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                if (progressListener != null) {
                    Duration total = player.getTotalDuration();
                    if (total != null && !total.isUnknown()) {
                        progressListener.accept(newVal, total);
                    }
                }
            });

            // IMPORTANTE: Se guarda en el mapa RECIÉN cuando JavaFX terminó de procesar el archivo
            player.setOnReady(() -> {
                players.put(type, player);
                if (currentTrack == null) {
                    currentTrack = type;
                }

                // Notificamos a la UI de forma segura en su propio hilo
                Platform.runLater(() -> {
                    if (progressListener != null) {
                        Duration total = player.getTotalDuration();
                        if (total != null && !total.isUnknown()) {
                            progressListener.accept(Duration.ZERO, total);
                        }
                    }
                });
            });

        } catch (Exception e) {
            System.err.println("No se pudo cargar la pista " + type + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Hace el cambio dinámico manteniendo el tiempo actual de reproducción.
     */
    public void switchTrack(TrackType newTrack) {
        if (!players.containsKey(newTrack)) return;

        Duration currentTime = Duration.ZERO;
        if (currentTrack != null) {
            MediaPlayer currentPlayer = players.get(currentTrack);
            currentTime = currentPlayer.getCurrentTime();
            currentPlayer.pause();
        }

        currentTrack = newTrack;
        MediaPlayer nextPlayer = players.get(newTrack);
        nextPlayer.seek(currentTime);
        nextPlayer.play();
    }

    // Controles globales básicos
    public void play() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).play();
        }
    }

    public void pause() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).pause();
        }
    }

    public void stop() {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).stop();
        }
    }

    // Getters y Setters estructurales
    public boolean isLoaded(TrackType type) {
        return players.containsKey(type);
    }

    public void setLoop(boolean enabled) {
        this.loopEnabled = enabled;
    }

    public void setProgressListener(BiConsumer<Duration, Duration> listener) {
        this.progressListener = listener;
    }

    public void seekCurrentTrack(double fraction) {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            MediaPlayer player = players.get(currentTrack);
            Duration total = player.getTotalDuration();
            if (total != null && !total.isUnknown()) {
                player.seek(total.multiply(fraction));
            }
        }
    }
}