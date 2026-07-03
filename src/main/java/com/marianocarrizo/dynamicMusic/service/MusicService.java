package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.utils.ConversorUtils;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class MusicService {

    // Mapa principal de reproductores por estado de ánimo
    private final Map<TrackType, MediaPlayer> players;

    // Nombres de archivo por tipo, para actualizar el label "ahora sonando" (G5)
    private final Map<TrackType, String> trackNames;

    private TrackType currentTrack;
    private boolean loopEnabled;
    private BiConsumer<Duration, Duration> progressListener;
    private BiConsumer<Duration, Duration> previewProgressListener;

    // G4: reproductor dedicado a preescucha — nunca entra al mapa 'players'
    private MediaPlayer previewPlayer;

    // G5: propiedad reactiva con el nombre de la pista actualmente sonando
    private final StringProperty nowPlaying = new SimpleStringProperty("—");

    /**
     * G4 + G6: executor de un único hilo para la preparación de audio.
     * Se usa tanto en loadTrack (conversión FLAC + RMS) como en previewTrack.
     * El hilo es daemon para no bloquear el cierre de la JVM.
     */
    private final ExecutorService audioPrep = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-prep");
        t.setDaemon(true);
        return t;
    });

    public MusicService() {
        this.players = new HashMap<>();
        this.trackNames = new EnumMap<>(TrackType.class);
        this.currentTrack = null;
        this.loopEnabled = false;
        this.progressListener = null;
        this.previewProgressListener = null;
        this.previewPlayer = null;
    }

    /**
     * G5: expone la propiedad de solo lectura del nombre de la pista activa.
     */
    public ReadOnlyStringProperty nowPlayingProperty() {
        return nowPlaying;
    }

    /**
     * G6: carga un archivo de audio en un slot de manera asíncrona.
     * La conversión FLAC y el cálculo de RMS ocurren en el hilo 'audio-prep'.
     * La construcción del MediaPlayer y toda mutación de JavaFX ocurren
     * en el hilo FX vía Platform.runLater, para evitar bloqueos en la UI.
     */
    public void loadTrack(TrackType type, File file) {
        if (file == null || !file.exists()) return;

        final String trackDisplayName = file.getName();

        audioPrep.submit(() -> {
            try {
                // Conversión de formato si es necesario (fuera del hilo FX)
                final File finalFile;
                if (file.getName().toLowerCase().endsWith(".flac")) {
                    finalFile = ConversorUtils.convertFlacToWav(file);
                } else {
                    finalFile = file;
                }

                // Cálculo de ganancia RMS (fuera del hilo FX — puede ser costoso)
                final double gain = ConversorUtils.calculateGainForRMS(finalFile);

                // Toda construcción y mutación de JavaFX se delega al hilo FX
                Platform.runLater(() -> {
                    String sourceUrl = finalFile.toURI().toString();
                    Media media = new Media(sourceUrl);
                    MediaPlayer player = new MediaPlayer(media);

                    player.setVolume(gain);

                    // Manejo de errores nativos de JavaFX Media
                    player.setOnError(() ->
                            System.err.println("Error en pista " + type + ": " + player.getError()));

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

                    // players.put se hace dentro de setOnReady (en el hilo FX)
                    // para evitar que switchTrack acceda al reproductor antes de que esté listo
                    player.setOnReady(() -> {
                        players.put(type, player);
                        trackNames.put(type, trackDisplayName);
                        if (currentTrack == null) {
                            currentTrack = type;
                        }

                        // Notificamos a la UI del estado inicial de progreso
                        if (progressListener != null) {
                            Platform.runLater(() -> {
                                Duration total = player.getTotalDuration();
                                if (total != null && !total.isUnknown()) {
                                    progressListener.accept(Duration.ZERO, total);
                                }
                            });
                        }
                    });
                });

            } catch (Exception e) {
                System.err.println("No se pudo cargar la pista " + type + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * G4 + G5: hace el cambio dinámico de mood manteniendo el tiempo actual.
     * Detiene la preescucha activa antes de iniciar el reproductor de mood.
     */
    public void switchTrack(TrackType newTrack) {
        if (!players.containsKey(newTrack)) return;

        // Detener la preescucha si estaba activa (G4)
        if (previewPlayer != null) {
            previewPlayer.stop();
        }

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

        // G5: actualizar el label con el nombre de la pista del nuevo mood
        nowPlaying.set(trackNames.getOrDefault(newTrack, newTrack.name()));
    }

    /**
     * G4: inicia la preescucha de un archivo sin afectar los slots de mood asignados.
     * Secuencia: (1) pausa el mood activo, (2) descarta preescucha previa,
     * (3) convierte + calcula RMS en background, (4) construye y arranca el
     * reproductor en el hilo FX.
     */
    public void previewTrack(File file) {
        if (file == null || !file.exists()) return;

        // (1) Pausar el reproductor de mood activo si estaba sonando
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).pause();
        }

        // (2) Detener y descartar la preescucha previa si existía
        if (previewPlayer != null) {
            previewPlayer.stop();
            previewPlayer.dispose();
            previewPlayer = null;
        }

        // (3) Preparación en background (conversión FLAC + RMS)
        audioPrep.submit(() -> {
            try {
                final File finalFile;
                if (file.getName().toLowerCase().endsWith(".flac")) {
                    finalFile = ConversorUtils.convertFlacToWav(file);
                } else {
                    finalFile = file;
                }

                final double gain = ConversorUtils.calculateGainForRMS(finalFile);
                final String previewLabel = "▶ " + file.getName();

                // (4) Construcción del reproductor en el hilo FX
                Platform.runLater(() -> {
                    try {
                        String sourceUrl = finalFile.toURI().toString();
                        Media media = new Media(sourceUrl);
                        MediaPlayer player = new MediaPlayer(media);
                        player.setVolume(gain);

                        // La preescucha no hace loop: setOnEndOfMedia restaura el label
                        player.setOnEndOfMedia(() -> {
                            Platform.runLater(() -> {
                                // Restaurar al nombre del mood activo, o "—" si no había ninguno
                                String restoredName = currentTrack != null
                                        ? trackNames.getOrDefault(currentTrack, "—")
                                        : "—";
                                nowPlaying.set(restoredName);
                                // Limpiar el reproductor de preescucha una vez terminado
                                if (previewPlayer != null) {
                                    previewPlayer.dispose();
                                    previewPlayer = null;
                                }
                            });
                        });

                        // Listener de progreso exclusivo del panel de preescucha
                        player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                            if (previewProgressListener != null) {
                                Duration total = player.getTotalDuration();
                                if (total != null && !total.isUnknown()) {
                                    previewProgressListener.accept(newVal, total);
                                }
                            }
                        });

                        previewPlayer = player;

                        // Arrancar cuando el reproductor esté listo y actualizar el label (G5)
                        player.setOnReady(() -> {
                            player.play();
                            nowPlaying.set(previewLabel);
                            if (previewProgressListener != null) {
                                previewProgressListener.accept(Duration.ZERO, player.getTotalDuration());
                            }
                        });

                    } catch (Exception e) {
                        System.err.println("Error al iniciar preescucha de "
                                + file.getName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                System.err.println("Error en preparación de preescucha de "
                        + file.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * G4: detiene y descarta el reproductor de preescucha activo.
     */
    public void stopPreview() {
        if (previewPlayer != null) {
            previewPlayer.stop();
            previewPlayer.dispose();
            previewPlayer = null;
        }
    }

    // --- Controles del reproductor de mood ---

    public void play() {
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).play();
    }

    public void pause() {
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).pause();
    }

    public void stop() {
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).stop();
    }

    public boolean isPlaying() {
        if (currentTrack == null || !players.containsKey(currentTrack)) return false;
        return players.get(currentTrack).getStatus() == MediaPlayer.Status.PLAYING;
    }

    // --- Controles del reproductor de preescucha ---

    public void previewPlay() {
        if (previewPlayer != null) previewPlayer.play();
    }

    public void previewPause() {
        if (previewPlayer != null) previewPlayer.pause();
    }

    public boolean isPreviewPlaying() {
        return previewPlayer != null && previewPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    public boolean isPreviewLoaded() {
        return previewPlayer != null;
    }

    public void setPreviewProgressListener(BiConsumer<Duration, Duration> listener) {
        this.previewProgressListener = listener;
    }

    public void seekPreview(double fraction) {
        if (previewPlayer != null) {
            Duration total = previewPlayer.getTotalDuration();
            if (total != null && !total.isUnknown())
                previewPlayer.seek(total.multiply(fraction));
        }
    }

    // Getters y setters estructurales
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
            if (total != null && !total.isUnknown())
                player.seek(total.multiply(fraction));
        }
    }
}
