package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;

public class PlayerController {
    @FXML private Button btnPaused;
    @FXML private Button btnRaged;
    @FXML private Button btnNormal;
    @FXML private Button btnAdvantage;
    @FXML private ToggleButton btnLoopIcon;
    @FXML private TextField txtSearch;
    @FXML private Slider progressSlider;
    @FXML private Label lblCurrentTime;
    @FXML private Label lblTotalTime;
    @FXML private ListView<File> lstTracks;
    @FXML private ComboBox<TrackType> cmbTrackType;

    private final MusicService musicService = new MusicService();
    private boolean isDraggingSlider = false;
    private TrackType currentActiveType = TrackType.NORMAL;
    private final javafx.collections.ObservableList<File> masterTrackList = javafx.collections.FXCollections.observableArrayList();

    @FXML
    public void initialize() {
        updateButtons();

        if (cmbTrackType != null) {
            cmbTrackType.getItems().setAll(TrackType.values());
            cmbTrackType.setValue(TrackType.NORMAL);
        }

        javafx.collections.transformation.FilteredList<File> filteredTracks =
                new javafx.collections.transformation.FilteredList<>(masterTrackList, p -> true);

        if (txtSearch != null) {
            txtSearch.textProperty().addListener((observable, oldValue, newValue) -> {
                filteredTracks.setPredicate(file -> {
                    if (newValue == null || newValue.isEmpty()) return true;
                    return file.getName().toLowerCase().contains(newValue.toLowerCase());
                });
            });
        }

        if (lstTracks != null) {
            lstTracks.setItems(filteredTracks);
            lstTracks.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(File item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.getName());
                }
            });
        }

        if (btnLoopIcon != null) {
            btnLoopIcon.selectedProperty().addListener((obs, oldVal, newVal) -> musicService.setLoop(newVal));
        }

        musicService.setProgressListener((current, total) -> {
            Platform.runLater(() -> {
                if (!isDraggingSlider && total != null && !total.isUnknown()) {
                    double frac = current.toMillis() / total.toMillis();
                    progressSlider.setValue(frac * 100);
                    lblCurrentTime.setText(formatDuration(current));
                    lblTotalTime.setText(formatDuration(total)); // Mantiene el tiempo total actualizado al final del slider
                }
                updateButtons();
            });
        });

        if (progressSlider != null) {
            progressSlider.setOnMousePressed(e -> isDraggingSlider = true);
            progressSlider.setOnMouseReleased(e -> {
                isDraggingSlider = false;
                musicService.seekCurrentTrack(progressSlider.getValue() / 100.0);
            });
        }

        Platform.runLater(() -> updateSliderColor(TrackType.NORMAL));
    }

    @FXML private void onLoadPaused() { loadTrack(TrackType.PAUSED); }
    @FXML private void onLoadRaged() { loadTrack(TrackType.RAGED); }
    @FXML private void onLoadNormal() { loadTrack(TrackType.NORMAL); }
    @FXML private void onLoadAdvantage() { loadTrack(TrackType.ADVANTAGE); }

    @FXML private void onSwitchToPaused() { switchTrack(TrackType.PAUSED); }
    @FXML private void onSwitchToRaged() { switchTrack(TrackType.RAGED); }
    @FXML private void onSwitchToNormal() { switchTrack(TrackType.NORMAL); }
    @FXML private void onSwitchToAdvantage() { switchTrack(TrackType.ADVANTAGE); }

    public void switchTrack(TrackType type) {
        if (type == null || !musicService.isLoaded(type)) {
            return;
        }

        musicService.switchTrack(type);
        this.currentActiveType = type;
        updateSliderColor(type);
        updateButtons();
    }

    @FXML private void onPlay() { musicService.play(); }
    @FXML private void onPause() { musicService.pause(); }
    @FXML private void onStop() { musicService.stop(); }

    private void loadTrack(TrackType type) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.flac"));
        File file = fileChooser.showOpenDialog(null);
        if (file != null) musicService.loadTrack(type, file);
    }

    private void updateButtons() {
        boolean hasPaused = musicService.isLoaded(TrackType.PAUSED);
        boolean hasRaged = musicService.isLoaded(TrackType.RAGED);
        boolean hasNormal = musicService.isLoaded(TrackType.NORMAL);
        boolean hasAdvantage = musicService.isLoaded(TrackType.ADVANTAGE);

        // 1. Deshabilitar nativamente
        if (btnPaused != null) btnPaused.setDisable(!hasPaused);
        if (btnRaged != null) btnRaged.setDisable(!hasRaged);
        if (btnNormal != null) btnNormal.setDisable(!hasNormal);
        if (btnAdvantage != null) btnAdvantage.setDisable(!hasAdvantage);

        // 2. Limpiar estilos inline
        if (btnPaused != null) btnPaused.setStyle("");
        if (btnRaged != null) btnRaged.setStyle("");
        if (btnNormal != null) btnNormal.setStyle("");
        if (btnAdvantage != null) btnAdvantage.setStyle("");

        // 3. Pintar con estilo inline SOLO si la fase actual está cargada y activa
        if (currentActiveType != null) {
            switch (currentActiveType) {
                case PAUSED -> { if (btnPaused != null && hasPaused) btnPaused.setStyle("-fx-background-color: #7f8c8d; -fx-text-fill: black; -fx-border-color: #95a5a6;"); }
                case RAGED -> { if (btnRaged != null && hasRaged) btnRaged.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-border-color: #e74c3c;"); }
                case NORMAL -> { if (btnNormal != null && hasNormal) btnNormal.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-border-color: #2ecc71;"); }
                case ADVANTAGE -> { if (btnAdvantage != null && hasAdvantage) btnAdvantage.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-border-color: #3498db;"); }
            }
        }
    }

    private void updateSliderColor(TrackType type) {
        if (type == null || progressSlider == null || progressSlider.getScene() == null) return;
        Parent rootWindow = progressSlider.getScene().getRoot();
        if (rootWindow == null) return;

        switch (type) {
            case PAUSED -> rootWindow.setId("window-paused");
            case RAGED -> rootWindow.setId("window-raged");
            case NORMAL -> rootWindow.setId("window-normal");
            case ADVANTAGE -> rootWindow.setId("window-advantage");
            default -> rootWindow.setId("");
        }
    }

    private String formatDuration(javafx.util.Duration dur) {
        int totalSeconds = (int) dur.toSeconds();
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Seleccionar carpeta de música");
        File folder = chooser.showDialog(null);
        if (folder == null || !folder.isDirectory()) return;

        File[] files = folder.listFiles(f -> f.isFile() && (f.getName().toLowerCase().endsWith(".mp3") || f.getName().toLowerCase().endsWith(".wav") || f.getName().toLowerCase().endsWith(".flac")));
        masterTrackList.clear();
        if (files != null) masterTrackList.addAll(files);
        if (txtSearch != null) txtSearch.clear();
    }

    @FXML
    private void onAssignTrack() {
        if (lstTracks == null || cmbTrackType == null) return;
        File selected = lstTracks.getSelectionModel().getSelectedItem();
        TrackType type = cmbTrackType.getValue();
        if (selected != null && type != null) musicService.loadTrack(type, selected);
    }

    @FXML
    private void onToggleLoop() {
        if (btnLoopIcon != null) {
            musicService.setLoop(btnLoopIcon.isSelected());
        }
    }
}