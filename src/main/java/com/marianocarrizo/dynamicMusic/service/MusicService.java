package com.marianocarrizo.dynamicMusic.service;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.utils.ConversorUtils;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class MusicService {
    public final Map<TrackType, MediaPlayer> players = new HashMap<>();
    private TrackType currentTrack = null;
    private boolean loopEnabled = false;
    private BiConsumer<Duration, Duration> progressListener;

    public void loadTrack(TrackType type, File file) {
        if (file == null || !file.exists()) return;

        try {
            if (file.getName().toLowerCase().endsWith(".flac")) {
                file = ConversorUtils.convertFlacToWav(file);
            }

            Media media = new Media(file.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);

            double gain = ConversorUtils.calculateGainForRMS(file);
            player.setVolume(gain);

            player.setOnError(() -> System.err.println("Error en pista " + type + ": " + player.getError()));

            player.setOnEndOfMedia(() -> {
                if (loopEnabled) {
                    player.seek(Duration.ZERO);
                    player.play();
                }
            });

            player.currentTimeProperty().addListener((obs, oldVal, newVal) -> {
                if (progressListener != null) {
                    Duration total = player.getTotalDuration();
                    if (total != null && !total.isUnknown()) progressListener.accept(newVal, total);
                }
            });
            player.setOnReady(() -> {
                if (progressListener != null) {
                    Duration total = player.getTotalDuration();
                    if (total != null && !total.isUnknown()) progressListener.accept(Duration.ZERO, total);
                }
            });

            players.put(type, player);
            if (currentTrack == null) currentTrack = type;

        } catch (Exception e) {
            System.err.println("No se pudo cargar la pista " + type + ": " + e.getMessage());
        }
    }

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

    public void play() { if (currentTrack != null) players.get(currentTrack).play(); }
    public void pause() { if (currentTrack != null) players.get(currentTrack).pause(); }
    public void stop() { if (currentTrack != null) players.get(currentTrack).stop(); }

    public boolean isLoaded(TrackType type) { return players.containsKey(type); }

    public void setLoop(boolean enabled) { this.loopEnabled = enabled; }
    public void setProgressListener(BiConsumer<Duration, Duration> listener) { this.progressListener = listener; }
    public TrackType getCurrentTrack() { return currentTrack; }

    public void seekCurrentTrack(double fraction) {
        if (currentTrack != null && players.containsKey(currentTrack)) {
            MediaPlayer player = players.get(currentTrack);
            Duration total = player.getTotalDuration();
            if (total != null && !total.isUnknown()) player.seek(total.multiply(fraction));
        }
    }

}
