package com.marianocarrizo.dynamicMusic.Controller;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.stage.FileChooser;

import java.io.File;

public class PlayerController {

    @FXML private Button btnPaused;
    @FXML private Button btnRaged;
    @FXML private Button btnNormal;
    @FXML private Button btnAdvantage;

    private final MusicService musicService = new MusicService();

    @FXML
    public void initialize() {
        updateButtons();
    }

    // Cargar pistas
    @FXML
    private void onLoadPaused() { loadTrack(TrackType.PAUSED); }
    @FXML
    private void onLoadRaged() { loadTrack(TrackType.RAGED); }
    @FXML
    private void onLoadNormal() { loadTrack(TrackType.NORMAL); }
    @FXML
    private void onLoadAdvantage() { loadTrack(TrackType.ADVANTAGE); }

    // Cambiar pista
    @FXML
    private void onSwitchToPaused() { musicService.switchTrack(TrackType.PAUSED); }
    @FXML
    private void onSwitchToRaged() { musicService.switchTrack(TrackType.RAGED); }
    @FXML
    private void onSwitchToNormal() { musicService.switchTrack(TrackType.NORMAL); }
    @FXML
    private void onSwitchToAdvantage() { musicService.switchTrack(TrackType.ADVANTAGE); }

    // Controles
    @FXML
    private void onPlay() { musicService.play(); }
    @FXML
    private void onPause() { musicService.pause(); }
    @FXML
    private void onStop() { musicService.stop(); }

    private void loadTrack(TrackType type) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.flac")
        );
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
}
