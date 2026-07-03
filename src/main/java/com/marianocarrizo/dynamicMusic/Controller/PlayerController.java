package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;

public class PlayerController {

    // Layout
    @FXML private VBox mainRoot;

    // Botones de mood
    @FXML private Button btnPaused;
    @FXML private Button btnNormal;
    @FXML private Button btnRaged;
    @FXML private Button btnAdvantage;

    // Panel de mood
    @FXML private ToggleButton btnLoopIcon;
    @FXML private Button btnPlayPause;
    @FXML private Slider progressSlider;
    @FXML private Label lblCurrentTime;
    @FXML private Label lblTotalTime;

    // Panel de preescucha
    @FXML private Button btnPreviewPlayPause;
    @FXML private Slider previewSlider;
    @FXML private Label lblPreviewCurrentTime;
    @FXML private Label lblPreviewTotalTime;
    @FXML private Label lblPreviewTrack;

    // Label general
    @FXML private Label lblNowPlaying;

    // Lista y búsqueda
    @FXML private TextField txtSearch;
    @FXML private ListView<File> lstTracks;
    @FXML private ComboBox<TrackType> cmbTrackType;

    private final MusicService musicService = new MusicService();
    private final SessionConfigManager manager = new SessionConfigManager();
    private final SessionConfig sessionConfig = new SessionConfig();

    private boolean isDraggingSlider = false;
    private boolean isDraggingPreviewSlider = false;
    private TrackType currentActiveType = TrackType.NORMAL;

    private final javafx.collections.ObservableList<File> masterTrackList =
            javafx.collections.FXCollections.observableArrayList();

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
            txtSearch.textProperty().addListener((obs, oldVal, newVal) ->
                filteredTracks.setPredicate(file -> {
                    if (newVal == null || newVal.isEmpty()) return true;
                    return file.getName().toLowerCase().contains(newVal.toLowerCase());
                }));
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

            // Doble clic inicia preescucha en el panel separado
            lstTracks.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    File f = lstTracks.getSelectionModel().getSelectedItem();
                    if (f != null) startPreview(f);
                }
            });
        }

        if (btnLoopIcon != null) {
            btnLoopIcon.selectedProperty().addListener((obs, oldVal, newVal) ->
                musicService.setLoop(newVal));
        }

        // Listener del reproductor de mood
        musicService.setProgressListener((current, total) ->
            Platform.runLater(() -> {
                if (!isDraggingSlider && total != null && !total.isUnknown()) {
                    progressSlider.setValue(current.toMillis() / total.toMillis() * 100);
                    lblCurrentTime.setText(formatDuration(current));
                    lblTotalTime.setText(formatDuration(total));
                }
                updateButtons();
                updatePlayPauseButton();
            }));

        // Listener del reproductor de preescucha
        musicService.setPreviewProgressListener((current, total) ->
            Platform.runLater(() -> {
                if (!isDraggingPreviewSlider && total != null && !total.isUnknown()) {
                    previewSlider.setValue(current.toMillis() / total.toMillis() * 100);
                    lblPreviewCurrentTime.setText(formatDuration(current));
                    lblPreviewTotalTime.setText(formatDuration(total));
                }
                updatePreviewPlayPauseButton();
            }));

        if (progressSlider != null) {
            progressSlider.setOnMousePressed(e -> isDraggingSlider = true);
            progressSlider.setOnMouseReleased(e -> {
                isDraggingSlider = false;
                musicService.seekCurrentTrack(progressSlider.getValue() / 100.0);
            });
        }

        if (previewSlider != null) {
            previewSlider.setOnMousePressed(e -> isDraggingPreviewSlider = true);
            previewSlider.setOnMouseReleased(e -> {
                isDraggingPreviewSlider = false;
                musicService.seekPreview(previewSlider.getValue() / 100.0);
            });
        }

        if (lblNowPlaying != null) {
            lblNowPlaying.textProperty().bind(musicService.nowPlayingProperty());
        }

        updatePreviewPanel(false);
        Platform.runLater(() -> updateWindowMood(TrackType.NORMAL));
    }

    // --- Controles de mood ---

    @FXML private void onSwitchToPaused()   { switchTrack(TrackType.PAUSED); }
    @FXML private void onSwitchToNormal()   { switchTrack(TrackType.NORMAL); }
    @FXML private void onSwitchToRaged()    { switchTrack(TrackType.RAGED); }
    @FXML private void onSwitchToAdvantage(){ switchTrack(TrackType.ADVANTAGE); }

    public void switchTrack(TrackType type) {
        if (type == null || !musicService.isLoaded(type)) return;
        if (type == currentActiveType) return;
        musicService.switchTrack(type);
        this.currentActiveType = type;
        updateWindowMood(type);
        updateButtons();
        setMoodButtonIcon("⏸"); // siempre arranca reproduciendo
        updatePreviewPanel(false);
        resetPreviewSlider();
    }

    @FXML
    private void onTogglePlayPause() {
        if (musicService.isPlaying()) {
            musicService.pause();
            setMoodButtonIcon("▶");
        } else {
            musicService.play();
            setMoodButtonIcon("⏸");
        }
    }

    @FXML
    private void onStop() {
        musicService.stop();
        setMoodButtonIcon("▶");
    }

    private void setMoodButtonIcon(String icon) {
        if (btnPlayPause != null) btnPlayPause.setText(icon);
    }

    // Llamado desde el listener de progreso para sync en casos edge (fin de pista, etc.)
    private void updatePlayPauseButton() {
        if (btnPlayPause != null)
            btnPlayPause.setText(musicService.isPlaying() ? "⏸" : "▶");
    }

    // --- Controles de preescucha ---

    private void startPreview(File file) {
        if (lblPreviewTrack != null)
            lblPreviewTrack.setText(file.getName());
        musicService.previewTrack(file);
        updatePreviewPanel(true);
        updatePreviewPlayPauseButton();
    }

    @FXML
    private void onTogglePreviewPlayPause() {
        if (musicService.isPreviewPlaying()) {
            musicService.previewPause();
            setPreviewButtonIcon("▶");
        } else {
            musicService.previewPlay();
            setPreviewButtonIcon("⏸");
        }
    }

    private void setPreviewButtonIcon(String icon) {
        if (btnPreviewPlayPause != null) btnPreviewPlayPause.setText(icon);
    }

    @FXML
    private void onPreviewStop() {
        musicService.stopPreview();
        updatePreviewPanel(false);
        resetPreviewSlider();
    }

    // Llamado desde el listener de progreso del preview
    private void updatePreviewPlayPauseButton() {
        if (btnPreviewPlayPause != null)
            btnPreviewPlayPause.setText(musicService.isPreviewPlaying() ? "⏸" : "▶");
    }

    private void updatePreviewPanel(boolean active) {
        if (btnPreviewPlayPause != null) btnPreviewPlayPause.setDisable(!active);
        // El botón stop del preview se deshabilita si no hay nada cargado
        // (accedemos por el padre del botón no por fx:id — lo resolvemos por disable)
        if (previewSlider != null) previewSlider.setDisable(!active);
        if (!active && lblPreviewTrack != null)
            lblPreviewTrack.setText("Sin preescucha activa");
    }

    private void resetPreviewSlider() {
        if (previewSlider != null) previewSlider.setValue(0);
        if (lblPreviewCurrentTime != null) lblPreviewCurrentTime.setText("0:00");
        if (lblPreviewTotalTime != null)   lblPreviewTotalTime.setText("0:00");
        updatePreviewPlayPauseButton();
    }

    // --- Estado de botones de mood ---

    private void updateButtons() {
        boolean hasPaused    = musicService.isLoaded(TrackType.PAUSED);
        boolean hasNormal    = musicService.isLoaded(TrackType.NORMAL);
        boolean hasRaged     = musicService.isLoaded(TrackType.RAGED);
        boolean hasAdvantage = musicService.isLoaded(TrackType.ADVANTAGE);

        if (btnPaused != null)    btnPaused.setDisable(!hasPaused);
        if (btnNormal != null)    btnNormal.setDisable(!hasNormal);
        if (btnRaged != null)     btnRaged.setDisable(!hasRaged);
        if (btnAdvantage != null) btnAdvantage.setDisable(!hasAdvantage);

        // Limpiar clase activa de todos y reasignarla al mood actual
        for (Button b : new Button[]{btnPaused, btnNormal, btnRaged, btnAdvantage}) {
            if (b != null) b.getStyleClass().remove("btn-phase-active");
        }

        if (currentActiveType != null) {
            Button active = switch (currentActiveType) {
                case PAUSED    -> btnPaused;
                case NORMAL    -> btnNormal;
                case RAGED     -> btnRaged;
                case ADVANTAGE -> btnAdvantage;
            };
            if (active != null && musicService.isLoaded(currentActiveType))
                active.getStyleClass().add("btn-phase-active");
        }
    }

    private void updateWindowMood(TrackType type) {
        if (type == null || mainRoot == null) return;
        mainRoot.setId(switch (type) {
            case PAUSED    -> "window-paused";
            case RAGED     -> "window-raged";
            case NORMAL    -> "window-normal";
            case ADVANTAGE -> "window-advantage";
        });
    }

    // --- Sesión ---

    @FXML
    private void onAssignTrack() {
        if (lstTracks == null || cmbTrackType == null) return;
        File selected = lstTracks.getSelectionModel().getSelectedItem();
        TrackType type = cmbTrackType.getValue();
        if (selected != null && type != null) {
            musicService.loadTrack(type, selected);
            sessionConfig.put(type, selected.getAbsolutePath());
            manager.save(sessionConfig);
        }
    }

    public void applySession(SessionConfig cfg) {
        if (cfg == null) return;
        cfg.getAssignments().forEach((type, path) -> {
            File file = new File(path);
            if (file.exists()) {
                musicService.loadTrack(type, file);
                sessionConfig.put(type, path);
            }
        });
    }

    // --- Carpeta y búsqueda ---

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Seleccionar carpeta de música");
        File folder = chooser.showDialog(null);
        if (folder == null || !folder.isDirectory()) return;

        File[] files = folder.listFiles(f -> f.isFile() && (
                f.getName().toLowerCase().endsWith(".mp3") ||
                f.getName().toLowerCase().endsWith(".wav") ||
                f.getName().toLowerCase().endsWith(".flac")));
        masterTrackList.clear();
        if (files != null) masterTrackList.addAll(files);
        if (txtSearch != null) txtSearch.clear();
    }

    // --- Controles de ventana ---

    @FXML
    private void onToggleLoop() {
        if (btnLoopIcon != null) musicService.setLoop(btnLoopIcon.isSelected());
    }

    @FXML
    private void onCloseWindow(ActionEvent event) {
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void onMinimizeWindow(ActionEvent event) {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        stage.setIconified(true);
    }

    // --- Utils ---

    private String formatDuration(javafx.util.Duration dur) {
        int totalSeconds = (int) dur.toSeconds();
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }
}
