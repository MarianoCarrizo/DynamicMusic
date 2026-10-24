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

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class MusicService {

    // =========================================================================
    // T-07: Enum de modo de reproducción
    // =========================================================================

    /** Modo de reproducción activo. Solo un modo emite audio a la vez. */
    public enum PlaybackMode {
        MOOD,
        PLAYLIST,
        /** A single track played on request, independent of the scene (replaces mood/playlist audio). */
        ON_DEMAND
    }

    // =========================================================================
    // Campos existentes (mood players)
    // =========================================================================

    // Mapa principal de reproductores por estado de ánimo
    private final Map<TrackType, MediaPlayer> players;

    // Nombres de archivo por tipo, para actualizar el label "ahora sonando"
    private final Map<TrackType, String> trackNames;

    // Volumen RMS calculado por pista — necesario para restaurar al hacer fade-in
    private final Map<TrackType, Double> trackGains;

    // Per-slot load generation: lets unloadTrack/loadTrack discard stale in-flight loads (FX thread only)
    private final Map<TrackType, Integer> loadSeq = new EnumMap<>(TrackType.class);

    // Timeline de fade activo (mood o playlist) — se cancela si el usuario actúa antes
    private Timeline activeFade;

    private TrackType currentTrack;
    private boolean loopEnabled;
    private boolean previewLoopEnabled;
    private BiConsumer<Duration, Duration> progressListener;
    private BiConsumer<Duration, Duration> previewProgressListener;

    // Reproductor dedicado a preescucha — nunca entra al mapa 'players'
    private MediaPlayer previewPlayer;

    // Propiedad reactiva con el nombre de la pista actualmente sonando
    private final StringProperty nowPlaying = new SimpleStringProperty("—");

    // Amplitud de audio: escrita en el hilo del callback del espectro, leída por la UI
    private final AudioAnalyzer audioAnalyzer = new AudioAnalyzer();

    /**
     * Executor de un único hilo para la preparación de audio.
     * Se usa para conversión FLAC + cálculo RMS fuera del hilo FX.
     */
    private final ExecutorService audioPrep = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-prep");
        t.setDaemon(true);
        return t;
    });

    // =========================================================================
    // T-07: Campos del modo playlist
    // =========================================================================

    // Modo activo; por defecto MOOD al arrancar
    private PlaybackMode mode = PlaybackMode.MOOD;

    // Reproductor único del modo playlist — un solo MediaPlayer activo a la vez
    private MediaPlayer playlistPlayer = null;

    // Ganancia RMS de la pista actual del playlist (para restaurar después del fade)
    private double playlistGain = 1.0;

    // Volumen maestro global [0.0-1.0]; multiplicador sobre las ganancias RMS
    private double masterVolume = 1.0;

    // Ganancia RMS raw de la preescucha activa (necesaria para live-reapply del volumen maestro)
    private double previewGain = 1.0;

    // On-demand source: one player, created only when the user asks for it
    private MediaPlayer onDemandPlayer = null;
    private double onDemandGain = 1.0;
    private boolean onDemandLoop = false;
    // Bumped on every mode request; discards on-demand loads that were superseded while preparing
    private int onDemandSeq = 0;
    private BiConsumer<Duration, Duration> onDemandProgressListener = null;
    private Runnable onDemandStateListener = null;

    // Callback que el controlador suministra para avanzar al siguiente track
    private Runnable onPlaylistEnd = null;

    // Listener de progreso para el panel de progreso del playlist
    private BiConsumer<Duration, Duration> playlistProgressListener = null;

    // =========================================================================
    // Constructor
    // =========================================================================

    public MusicService() {
        this.players = new HashMap<>();
        this.trackNames = new EnumMap<>(TrackType.class);
        this.trackGains = new EnumMap<>(TrackType.class);
        this.currentTrack = null;
        this.loopEnabled = false;
        this.previewLoopEnabled = false;
        this.progressListener = null;
        this.previewProgressListener = null;
        this.previewPlayer = null;
    }

    // =========================================================================
    // Volumen maestro global
    // =========================================================================

    /** Único punto de escala: toda ganancia RAW debe pasar por aquí antes de llegar a setVolume. */
    private double effectiveGain(double rawGain) {
        return rawGain * masterVolume;
    }

    /**
     * Actualiza el volumen maestro y lo reaplica en tiempo real a todos los reproductores activos.
     * No toca el reproductor de ambiance (INV-5).
     */
    public void setMasterVolume(double v) {
        this.masterVolume = Math.max(0.0, Math.min(1.0, v));
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).setVolume(
                effectiveGain(trackGains.getOrDefault(currentTrack, 1.0)));
        }
        if (playlistPlayer != null) {
            playlistPlayer.setVolume(effectiveGain(playlistGain));
        }
        if (previewPlayer != null) {
            previewPlayer.setVolume(effectiveGain(previewGain));
        }
        if (onDemandPlayer != null) {
            onDemandPlayer.setVolume(effectiveGain(onDemandGain));
        }
    }

    public double getMasterVolume() {
        return masterVolume;
    }

    // =========================================================================
    // Propiedad nowPlaying + amplitud
    // =========================================================================

    public ReadOnlyStringProperty nowPlayingProperty() {
        return nowPlaying;
    }

    /** Latest analyzed audio features (bands, energy, beat). Decays to silence when no audio flows. */
    public AudioFeatures getAudioFeatures() {
        return audioAnalyzer.current();
    }

    /**
     * Adjunta el listener de espectro de audio a un reproductor.
     * Alimenta el AudioAnalyzer (bandas, energia, beats); el aura lo lee via getAudioFeatures().
     * Es seguro llamar desde cualquier hilo.
     */
    private void attachSpectrumListener(MediaPlayer player) {
        audioAnalyzer.reset(); // new track: drop adaptive normalization state
        player.setAudioSpectrumInterval(0.025); // 40 Hz: lower latency for the aura; AudioAnalyzer.TICK_NS matches
        player.setAudioSpectrumNumBands(AudioAnalyzer.NUM_BANDS);
        player.setAudioSpectrumThreshold(-75); // default -60 dB flattens the quiet high band
        player.setAudioSpectrumListener((timestamp, duration, magnitudes, phases) -> {
            audioAnalyzer.process(magnitudes, timestamp);
        });
    }

    // =========================================================================
    // T-07: setPlaybackMode — cambio de modo con teardown del modo saliente
    // =========================================================================

    /**
     * Cambia el modo de reproducción activo.
     * Debe invocarse desde el hilo FX.
     */
    public void setPlaybackMode(PlaybackMode target) {
        // Advertencia si se llama fuera del hilo FX (T-17)
        if (!Platform.isFxApplicationThread()) {
            System.err.println("ADVERTENCIA: setPlaybackMode fue invocado fuera del hilo FX");
        }
        // Any explicit mode request supersedes an on-demand load still preparing
        if (target != PlaybackMode.ON_DEMAND) onDemandSeq++;
        if (mode == target) return;

        // --- Teardown del modo saliente ---
        if (mode == PlaybackMode.ON_DEMAND) {
            cancelActiveFade();
            disposeOnDemandPlayer();
        } else if (mode == PlaybackMode.MOOD) {
            // Saliendo del modo MOOD: cancelar fade, pausar el reproductor activo,
            // desconectar el listener de espectro
            cancelActiveFade();
            if (currentTrack != null && players.containsKey(currentTrack)) {
                MediaPlayer moodPlayer = players.get(currentTrack);
                if (moodPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                    moodPlayer.pause();
                }
                moodPlayer.setAudioSpectrumListener(null);
            }
        } else {
            // Saliendo del modo PLAYLIST: cancelar fade, detener y liberar el reproductor
            cancelActiveFade();
            if (playlistPlayer != null) {
                playlistPlayer.stop();
                playlistPlayer.setAudioSpectrumListener(null); // T-16
                playlistPlayer.dispose();
                playlistPlayer = null;
            }
        }

        mode = target;

        // --- Setup del modo entrante ---
        if (mode == PlaybackMode.MOOD) {
            // Entrando al modo MOOD: reconectar el listener de espectro al reproductor activo
            // T-16: solo si el reproductor existe
            if (currentTrack != null && players.containsKey(currentTrack)) {
                attachSpectrumListener(players.get(currentTrack));
            }
            // Playlist/on-demand may have overwritten the label
            nowPlaying.set(currentTrack != null ? trackNames.getOrDefault(currentTrack, "—") : "—");
            // No se auto-reproduce; el controlador decide
        }
        // Entrando al modo PLAYLIST: playlistPlayer ya es null; el controlador
        // llamará a playPlaylistTrack(...) cuando el usuario pulse play
    }

    public PlaybackMode getPlaybackMode() {
        return mode;
    }

    // =========================================================================
    // loadTrack (existente, con guard de modo — T-08)
    // =========================================================================

    /**
     * Carga un archivo de audio en un slot de mood de forma asíncrona.
     * La conversión FLAC y el cálculo de RMS ocurren en el hilo 'audio-prep'.
     * La construcción del MediaPlayer y toda mutación de JavaFX ocurren en el
     * hilo FX vía Platform.runLater.
     */
    public void loadTrack(TrackType type, File file) {
        loadTrack(type, file, null);
    }

    /**
     * Same as {@link #loadTrack(TrackType, File)}, with a callback run on the FX thread once
     * the new player is ready and installed. A load superseded by a newer load or by
     * {@link #unloadTrack(TrackType)} for the same slot is dropped and never calls back.
     * If the slot already had a player it is disposed; when that player was the current
     * track and was playing, the new one starts playing in its place.
     */
    public void loadTrack(TrackType type, File file, Runnable onLoaded) {
        loadTrack(type, file, onLoaded, null);
    }

    /**
     * Same as {@link #loadTrack(TrackType, File, Runnable)}; {@code onFailed} runs on the FX
     * thread when this (still current) load cannot produce a playable player.
     */
    public void loadTrack(TrackType type, File file, Runnable onLoaded, Runnable onFailed) {
        if (file == null || !file.exists()) return;

        final String trackDisplayName = file.getName();
        final int seq = loadSeq.merge(type, 1, Integer::sum);

        audioPrep.submit(() -> {
            try {
                final File finalFile;
                if (file.getName().toLowerCase().endsWith(".flac")) {
                    finalFile = ConversorUtils.convertFlacToWav(file);
                } else {
                    finalFile = file;
                }

                final double gain = ConversorUtils.calculateGainForRMS(finalFile);

                Platform.runLater(() -> {
                    if (loadSeq.getOrDefault(type, 0) != seq) return; // superseded or unloaded
                    String sourceUrl = finalFile.toURI().toString();
                    final MediaPlayer player;
                    try {
                        player = new MediaPlayer(new Media(sourceUrl));
                    } catch (RuntimeException ex) {
                        System.err.println("No se pudo abrir la pista " + type + ": " + ex.getMessage());
                        if (onFailed != null) onFailed.run();
                        return;
                    }

                    player.setVolume(effectiveGain(gain));

                    player.setOnError(() -> {
                        System.err.println("Error en pista " + type + ": " + player.getError());
                        Platform.runLater(() -> {
                            // Failed before it was installed: report it so the caller can retry later
                            if (players.get(type) != player && loadSeq.getOrDefault(type, 0) == seq) {
                                player.dispose();
                                if (onFailed != null) onFailed.run();
                            }
                        });
                    });

                    // T-08: el loop del mood no debe dispararse mientras estemos en modo PLAYLIST
                    player.setOnEndOfMedia(() -> {
                        if (mode != PlaybackMode.MOOD) return; // guard T-08
                        if (loopEnabled) {
                            player.seek(Duration.ZERO);
                            player.play();
                        }
                    });

                    player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                        if (progressListener != null) {
                            Duration total = player.getTotalDuration();
                            if (total != null && !total.isUnknown()) {
                                progressListener.accept(newVal, total);
                            }
                        }
                    });

                    player.setOnReady(() -> {
                        if (loadSeq.getOrDefault(type, 0) != seq) {
                            player.dispose(); // superseded while preparing
                            return;
                        }
                        boolean resumePlayback = false;
                        MediaPlayer replaced = players.put(type, player);
                        if (replaced != null) {
                            if (type == currentTrack) {
                                cancelActiveFade();
                                resumePlayback = replaced.getStatus() == MediaPlayer.Status.PLAYING;
                            }
                            replaced.setAudioSpectrumListener(null);
                            replaced.stop();
                            replaced.dispose();
                        }
                        trackNames.put(type, trackDisplayName);
                        trackGains.put(type, gain);
                        attachSpectrumListener(player);
                        if (currentTrack == null) {
                            currentTrack = type;
                        }
                        if (type == currentTrack && replaced != null) {
                            nowPlaying.set(trackDisplayName); // swapped in place: keep the label in sync
                            if (resumePlayback) player.play();
                        }
                        if (onLoaded != null) onLoaded.run();
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
                if (onFailed != null) {
                    Platform.runLater(() -> {
                        if (loadSeq.getOrDefault(type, 0) == seq) onFailed.run();
                    });
                }
            }
        });
    }

    /**
     * Releases the player of a mood slot. If it was the current track, playback stops and
     * there is no current track until another one is selected or loaded.
     * Pending loads for the slot are cancelled. Must be called on the FX thread.
     */
    public void unloadTrack(TrackType type) {
        if (type == null) return;
        loadSeq.merge(type, 1, Integer::sum); // invalidate in-flight loads
        MediaPlayer removed = players.remove(type);
        trackNames.remove(type);
        trackGains.remove(type);
        if (removed == null) return;
        if (type == currentTrack) {
            cancelActiveFade();
            currentTrack = null;
            nowPlaying.set("—");
        }
        removed.setAudioSpectrumListener(null);
        removed.stop();
        removed.dispose();
    }

    /** Current mood slot, or null when nothing is selected. */
    public TrackType getCurrentTrack() {
        return currentTrack;
    }

    /**
     * Makes a loaded slot the current track WITHOUT starting playback (the previous
     * current player is paused). No-op if the slot is not loaded or already current.
     */
    public void selectTrack(TrackType type) {
        if (mode != PlaybackMode.MOOD) return;
        if (type == null || type == currentTrack || !players.containsKey(type)) return;
        cancelActiveFade();
        if (currentTrack != null && players.containsKey(currentTrack)) {
            MediaPlayer old = players.get(currentTrack);
            old.pause();
            old.setAudioSpectrumListener(null);
        }
        currentTrack = type;
        attachSpectrumListener(players.get(type));
        nowPlaying.set(trackNames.getOrDefault(type, type.name()));
    }

    // =========================================================================
    // switchTrack (existente, con guard de modo — T-08)
    // =========================================================================

    /**
     * Cambia de mood manteniendo el tiempo de reproducción actual.
     * No-op mientras el modo activo sea PLAYLIST (T-08).
     */
    public void switchTrack(TrackType newTrack) {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        if (!players.containsKey(newTrack)) return;

        cancelActiveFade();

        if (previewPlayer != null) {
            previewPlayer.stop();
        }

        Duration currentTime = Duration.ZERO;
        if (currentTrack != null) {
            MediaPlayer oldPlayer = players.get(currentTrack);
            currentTime = oldPlayer.getCurrentTime();
            oldPlayer.pause();
            oldPlayer.setAudioSpectrumListener(null);
        }

        currentTrack = newTrack;
        MediaPlayer nextPlayer = players.get(newTrack);
        attachSpectrumListener(nextPlayer);
        nextPlayer.seek(currentTime);
        nextPlayer.play();

        nowPlaying.set(trackNames.getOrDefault(newTrack, newTrack.name()));
    }

    // =========================================================================
    // previewTrack (existente, con pausa del playlist player — T-08)
    // =========================================================================

    /**
     * Inicia la preescucha de un archivo sin afectar los slots de mood asignados.
     * Pausa el reproductor de mood activo y, si hay uno de playlist sonando, también lo pausa.
     */
    public void previewTrack(File file) {
        if (file == null || !file.exists()) return;

        // (1) Pausar el reproductor de mood activo si estaba sonando
        if (currentTrack != null && players.containsKey(currentTrack)) {
            players.get(currentTrack).pause();
        }
        // T-08: pausar también el reproductor de playlist si está sonando
        if (playlistPlayer != null && playlistPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            playlistPlayer.pause();
        }

        if (onDemandPlayer != null && onDemandPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
            onDemandPlayer.pause();
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
                        previewGain = gain;
                        player.setVolume(effectiveGain(gain));

                        player.setOnEndOfMedia(() -> {
                            Platform.runLater(() -> {
                                if (previewLoopEnabled) {
                                    player.seek(Duration.ZERO);
                                    player.play();
                                } else {
                                    String restoredName = currentTrack != null
                                            ? trackNames.getOrDefault(currentTrack, "—")
                                            : "—";
                                    nowPlaying.set(restoredName);
                                    if (previewPlayer != null) {
                                        previewPlayer.dispose();
                                        previewPlayer = null;
                                    }
                                }
                            });
                        });

                        player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                            if (previewProgressListener != null) {
                                Duration total = player.getTotalDuration();
                                if (total != null && !total.isUnknown()) {
                                    previewProgressListener.accept(newVal, total);
                                }
                            }
                        });

                        previewPlayer = player;
                        attachSpectrumListener(player);

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

    public void stopPreview() {
        if (previewPlayer != null) {
            previewPlayer.stop();
            previewPlayer.dispose();
            previewPlayer = null;
        }
    }

    // =========================================================================
    // Controles del reproductor de mood (con guards de modo — T-08)
    // =========================================================================

    public void play() {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).play();
    }

    public void pause() {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).pause();
    }

    public void stop() {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        cancelActiveFade();
        if (currentTrack != null && players.containsKey(currentTrack))
            players.get(currentTrack).stop();
    }

    /**
     * Baja el volumen a 0 en 1 segundo, luego pausa y restaura el volumen.
     * No-op mientras el modo activo sea PLAYLIST (T-08).
     */
    public void fadeOutAndPause(Runnable onComplete) {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        cancelActiveFade();
        if (currentTrack == null || !players.containsKey(currentTrack)) return;
        MediaPlayer player = players.get(currentTrack);
        double savedVolume = player.getVolume();
        activeFade = new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(1000),
                new KeyValue(player.volumeProperty(), 0.0, Interpolator.EASE_IN)
            )
        );
        activeFade.setOnFinished(e -> {
            player.pause();
            player.setVolume(savedVolume);
            activeFade = null;
            if (onComplete != null) onComplete.run();
        });
        activeFade.play();
    }

    /**
     * Inicia la reproducción desde volumen 0 y sube al gain original en 500ms.
     * No-op mientras el modo activo sea PLAYLIST (T-08).
     */
    public void fadeInAndPlay() {
        if (mode != PlaybackMode.MOOD) return; // guard T-08
        cancelActiveFade();
        if (currentTrack == null || !players.containsKey(currentTrack)) return;
        MediaPlayer player = players.get(currentTrack);
        double targetVolume = effectiveGain(trackGains.getOrDefault(currentTrack, 1.0));
        player.setVolume(0.0);
        player.play();
        activeFade = new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(500),
                new KeyValue(player.volumeProperty(), targetVolume, Interpolator.EASE_OUT)
            )
        );
        activeFade.setOnFinished(e -> activeFade = null);
        activeFade.play();
    }

    /**
     * Cancela el fade activo y restaura el volumen original del reproductor
     * que corresponda al modo actual.
     */
    private void cancelActiveFade() {
        if (activeFade != null) {
            activeFade.stop();
            if (mode == PlaybackMode.PLAYLIST) {
                if (playlistPlayer != null) {
                    playlistPlayer.setVolume(effectiveGain(playlistGain));
                }
            } else {
                if (currentTrack != null && players.containsKey(currentTrack)) {
                    double gain = trackGains.getOrDefault(currentTrack, 1.0);
                    players.get(currentTrack).setVolume(effectiveGain(gain));
                }
            }
            activeFade = null;
        }
    }

    public boolean isPlaying() {
        if (currentTrack == null || !players.containsKey(currentTrack)) return false;
        return players.get(currentTrack).getStatus() == MediaPlayer.Status.PLAYING;
    }

    // =========================================================================
    // Controles del reproductor de preescucha
    // =========================================================================

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

    public void setPreviewLoop(boolean enabled) {
        this.previewLoopEnabled = enabled;
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

    // =========================================================================
    // T-09: playPlaylistTrack + métodos de transporte del playlist
    // =========================================================================

    /**
     * Carga y reproduce un archivo de audio en el modo playlist.
     * No-op si el modo activo no es PLAYLIST.
     * Reutiliza el pipeline asíncrono de loadTrack:
     *   audioPrep (FLAC→WAV + RMS) → Platform.runLater (construcción de MediaPlayer).
     */
    public void playPlaylistTrack(File file) {
        if (mode != PlaybackMode.PLAYLIST) return; // guard T-09

        audioPrep.submit(() -> {
            try {
                final File finalFile;
                if (file.getName().toLowerCase().endsWith(".flac")) {
                    finalFile = ConversorUtils.convertFlacToWav(file);
                } else {
                    finalFile = file;
                }

                final double gain = ConversorUtils.calculateGainForRMS(finalFile);

                Platform.runLater(() -> {
                    // T-17: re-verificar el modo dentro del runLater — la llamada async puede
                    // completarse después de un cambio de modo
                    if (mode != PlaybackMode.PLAYLIST) return;

                    // T-16 + T-17: desconectar espectro ANTES de dispose del reproductor anterior
                    if (playlistPlayer != null) {
                        playlistPlayer.setAudioSpectrumListener(null); // T-16
                        playlistPlayer.stop();
                        playlistPlayer.dispose();
                        playlistPlayer = null; // null después de dispose (T-17)
                    }

                    String sourceUrl = finalFile.toURI().toString();
                    Media media = new Media(sourceUrl);
                    MediaPlayer player = new MediaPlayer(media);

                    player.setVolume(effectiveGain(gain));
                    playlistGain = gain;

                    // T-16: adjuntar espectro ANTES de setOnReady (activo desde el primer frame)
                    attachSpectrumListener(player);

                    // Listener de progreso del panel de playlist
                    player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                        if (playlistProgressListener != null) {
                            Duration total = player.getTotalDuration();
                            if (total != null && !total.isUnknown()) {
                                playlistProgressListener.accept(newVal, total);
                            }
                        }
                    });

                    nowPlaying.set(file.getName());

                    // T-05/T-09: al llegar al fin de la pista, delegar la lógica de avance
                    // al callback suministrado por el controlador (onPlaylistEnd)
                    player.setOnEndOfMedia(() -> Platform.runLater(() -> {
                        if (mode == PlaybackMode.PLAYLIST && onPlaylistEnd != null) {
                            onPlaylistEnd.run();
                        }
                    }));

                    // En caso de error, avanzar para no quedar bloqueado en una pista mala
                    player.setOnError(() -> {
                        System.err.println("Error en pista de playlist: " + player.getError());
                        Platform.runLater(() -> {
                            // Skip the bad track only if this playlist player is still the audible source
                            if (mode == PlaybackMode.PLAYLIST && player == playlistPlayer && onPlaylistEnd != null) {
                                onPlaylistEnd.run();
                            }
                        });
                    });

                    // Arrancar la reproducción cuando el reproductor esté listo
                    player.setOnReady(() -> player.play());

                    playlistPlayer = player;
                });

            } catch (Exception e) {
                System.err.println("No se pudo cargar la pista de playlist: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Reanuda la reproducción del playlist si está pausado.
     * No-op si el modo activo no es PLAYLIST.
     */
    public void playlistPlay() {
        if (mode != PlaybackMode.PLAYLIST) return;
        if (playlistPlayer != null
                && playlistPlayer.getStatus() != MediaPlayer.Status.PLAYING) {
            playlistPlayer.play();
        }
    }

    /**
     * Pausa el reproductor de playlist con fade-out de 1 segundo.
     * No-op si el modo activo no es PLAYLIST.
     */
    public void playlistPause() {
        if (mode != PlaybackMode.PLAYLIST) return;
        if (playlistPlayer == null) return;
        if (playlistPlayer.getStatus() != MediaPlayer.Status.PLAYING) return;

        cancelActiveFade();
        final double savedGain = playlistGain;
        activeFade = new Timeline(
            new KeyFrame(
                javafx.util.Duration.millis(1000),
                new KeyValue(playlistPlayer.volumeProperty(), 0.0, Interpolator.EASE_IN)
            )
        );
        activeFade.setOnFinished(e -> {
            if (playlistPlayer != null) {
                playlistPlayer.pause();
                playlistPlayer.setVolume(effectiveGain(savedGain));
            }
            activeFade = null;
        });
        activeFade.play();
    }

    /**
     * Detiene y libera el reproductor de playlist.
     * T-17: null-guard antes de dispose; null del campo después de dispose.
     */
    public void stopPlaylist() {
        if (playlistPlayer != null) {
            playlistPlayer.stop();
            playlistPlayer.dispose();
            playlistPlayer = null;
        }
    }

    /**
     * Busca la posición en la pista actual del playlist.
     * No-op si el modo activo no es PLAYLIST.
     */
    public void seekPlaylist(double fraction) {
        if (mode != PlaybackMode.PLAYLIST) return;
        if (playlistPlayer != null) {
            Duration total = playlistPlayer.getTotalDuration();
            if (total != null && !total.isUnknown() && total.toMillis() > 0) {
                playlistPlayer.seek(total.multiply(fraction));
            }
        }
    }

    /** Retorna true si el reproductor de playlist está en estado PLAYING. */
    public boolean isPlaylistPlaying() {
        return playlistPlayer != null
                && playlistPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    /** Establece el callback que se invoca al llegar al fin de una pista del playlist. */
    public void setOnPlaylistEnd(Runnable r) {
        this.onPlaylistEnd = r;
    }

    /** Establece el listener de progreso para el panel de playlist. */
    public void setPlaylistProgressListener(BiConsumer<Duration, Duration> l) {
        this.playlistProgressListener = l;
    }

    // =========================================================================
    // ON_DEMAND: play any file immediately, replacing mood/playlist audio
    // =========================================================================

    private void disposeOnDemandPlayer() {
        if (onDemandPlayer != null) {
            onDemandPlayer.setAudioSpectrumListener(null);
            onDemandPlayer.stop();
            onDemandPlayer.dispose();
            onDemandPlayer = null;
        }
    }

    /**
     * Plays {@code file} right away, replacing whatever mood or playlist audio was playing.
     * The previous source is torn down only once the new player is prepared, so a failed or
     * superseded load leaves the current audio untouched. Must be called on the FX thread.
     *
     * @param onStarted run on the FX thread when the track starts playing
     */
    public void playOnDemand(File file, String title, Runnable onStarted) {
        if (file == null || !file.exists()) return;
        final int seq = ++onDemandSeq;

        audioPrep.submit(() -> {
            try {
                final File finalFile = file.getName().toLowerCase().endsWith(".flac")
                        ? ConversorUtils.convertFlacToWav(file) : file;
                final double gain = ConversorUtils.calculateGainForRMS(finalFile);

                Platform.runLater(() -> {
                    if (seq != onDemandSeq) return; // superseded by another request

                    // Build the new player first; the audible source is only swapped in setOnReady,
                    // so a file that fails to open leaves the current music playing
                    final MediaPlayer player;
                    try {
                        player = new MediaPlayer(new Media(finalFile.toURI().toString()));
                    } catch (RuntimeException ex) {
                        System.err.println("Could not open on-demand track: " + ex.getMessage());
                        return;
                    }

                    player.currentTimeProperty().addListener((obs, o, nv) -> {
                        if (onDemandProgressListener != null && player == onDemandPlayer) {
                            Duration total = player.getTotalDuration();
                            if (total != null && !total.isUnknown()) onDemandProgressListener.accept(nv, total);
                        }
                    });
                    player.setOnEndOfMedia(() -> Platform.runLater(() -> {
                        if (player != onDemandPlayer) return;
                        if (onDemandLoop) {
                            player.seek(Duration.ZERO);
                            player.play();
                        } else {
                            player.pause();
                            player.seek(Duration.ZERO); // stays loaded: replay or close
                            if (onDemandStateListener != null) onDemandStateListener.run();
                        }
                    }));
                    player.setOnError(() -> {
                        System.err.println("On-demand playback error: " + player.getError());
                        Platform.runLater(() -> {
                            if (player == onDemandPlayer) {
                                if (onDemandStateListener != null) onDemandStateListener.run();
                            } else {
                                player.dispose(); // failed before it became audible: nothing to undo
                            }
                        });
                    });
                    player.setOnReady(() -> {
                        if (seq != onDemandSeq) { // a mood/playlist/other track was picked meanwhile
                            player.dispose();
                            return;
                        }
                        setPlaybackMode(PlaybackMode.ON_DEMAND); // tears down mood/playlist
                        disposeOnDemandPlayer();                  // replaces a previous on-demand track
                        stopPreview();
                        onDemandGain = gain;
                        player.setVolume(effectiveGain(gain));
                        attachSpectrumListener(player);
                        onDemandPlayer = player;
                        nowPlaying.set(title != null ? title : file.getName());
                        player.play();
                        if (onDemandProgressListener != null)
                            onDemandProgressListener.accept(Duration.ZERO, player.getTotalDuration());
                        if (onStarted != null) onStarted.run();
                        if (onDemandStateListener != null) onDemandStateListener.run();
                    });
                });
            } catch (Exception e) {
                System.err.println("Could not load on-demand track: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void onDemandPlay() {
        if (mode == PlaybackMode.ON_DEMAND && onDemandPlayer != null) onDemandPlayer.play();
    }

    public void onDemandPause() {
        if (mode == PlaybackMode.ON_DEMAND && onDemandPlayer != null) onDemandPlayer.pause();
    }

    public boolean isOnDemandPlaying() {
        return mode == PlaybackMode.ON_DEMAND && onDemandPlayer != null
                && onDemandPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    public void seekOnDemand(double fraction) {
        if (onDemandPlayer == null) return;
        Duration total = onDemandPlayer.getTotalDuration();
        if (total != null && !total.isUnknown() && total.toMillis() > 0)
            onDemandPlayer.seek(total.multiply(fraction));
    }

    public void setOnDemandLoop(boolean enabled) {
        this.onDemandLoop = enabled;
    }

    public void setOnDemandProgressListener(BiConsumer<Duration, Duration> l) {
        this.onDemandProgressListener = l;
    }

    /** Called (FX thread) when the on-demand player starts, errors or reaches its end. */
    public void setOnDemandStateListener(Runnable r) {
        this.onDemandStateListener = r;
    }

    // =========================================================================
    // Getters y setters estructurales
    // =========================================================================

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
