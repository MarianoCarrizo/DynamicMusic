package com.marianocarrizo.dynamicMusic.Controller;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.HashMap;

public class PlayerController {
    @FXML private Button btnPaused;
    @FXML private Button btnRaged;
    @FXML private Button btnNormal;
    @FXML private Button btnAdvantage;
    @FXML private CheckBox chkLoop;
    @FXML private Slider progressSlider;
    @FXML private Label lblCurrentTime;
    @FXML private Label lblTotalTime;

    private final MusicService musicService = new MusicService( new HashMap<>(),null,null);
    private boolean isDraggingSlider = false;

    @FXML
    public void initialize() {
        updateButtons();

        // Loop
        chkLoop.selectedProperty().addListener((obs, oldVal, newVal) -> musicService.setLoop(newVal));

        // Progreso de la música
        musicService.setProgressListener((current, total) -> {
            if (!isDraggingSlider) {
                double frac = current.toMillis() / total.toMillis();
                progressSlider.setValue(frac * 100);
                lblCurrentTime.setText(formatDuration(current));
                lblTotalTime.setText(formatDuration(total));
            }
        });

        // Drag slider
        progressSlider.setOnMousePressed(e -> isDraggingSlider = true);
        progressSlider.setOnMouseReleased(e -> {
            isDraggingSlider = false;
            double frac = progressSlider.getValue() / 100.0;
            musicService.seekCurrentTrack(frac);
        });
    }

    // Cargar pistas
    @FXML private void onLoadPaused() { loadTrack(TrackType.PAUSED); }
    @FXML private void onLoadRaged() { loadTrack(TrackType.RAGED); }
    @FXML private void onLoadNormal() { loadTrack(TrackType.NORMAL); }
    @FXML private void onLoadAdvantage() { loadTrack(TrackType.ADVANTAGE); }

    @FXML private void onSwitchToPaused() { switchTrack(TrackType.PAUSED);
        updateSliderColor(TrackType.PAUSED);}
    @FXML private void onSwitchToRaged() { switchTrack(TrackType.RAGED);
        updateSliderColor(TrackType.RAGED);}
    @FXML private void onSwitchToNormal() { switchTrack(TrackType.NORMAL);
        updateSliderColor(TrackType.NORMAL);}
    @FXML private void onSwitchToAdvantage() { switchTrack(TrackType.ADVANTAGE);
        updateSliderColor(TrackType.ADVANTAGE);}

    // Cambiar pista (botones o teclado)
    public void switchTrack(TrackType type) {
        musicService.switchTrack(type);
        updateSliderColor(type);
    }

    // Controles
    @FXML private void onPlay() { musicService.play(); }
    @FXML private void onPause() { musicService.pause(); }
    @FXML private void onStop() { musicService.stop(); }

    private void loadTrack(TrackType type) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.flac"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) {
            musicService.loadTrack(type, file);
            updateButtons();
        }
    }

    private void updateButtons() {
        btnPaused.setDisable(!musicService.isLoaded(TrackType.PAUSED));
        btnRaged.setDisable(!musicService.isLoaded(TrackType.RAGED));
        btnNormal.setDisable(!musicService.isLoaded(TrackType.NORMAL));
        btnAdvantage.setDisable(!musicService.isLoaded(TrackType.ADVANTAGE));
    }

    private void updateSliderColor(TrackType type) {
        if (type == null) return;
        String color;
        switch (type) {
            case PAUSED -> color = "gray";
            case RAGED -> color = "red";
            case NORMAL -> color = "blue";
            case ADVANTAGE -> color = "green";
            default -> color = "black";
        }
        progressSlider.setStyle(color);
    }

    private String formatDuration(javafx.util.Duration dur) {
        int totalSeconds = (int) dur.toSeconds();
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
