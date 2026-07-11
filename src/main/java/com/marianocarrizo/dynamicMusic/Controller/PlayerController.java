package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.ambiance.AmbianceStage;
import com.marianocarrizo.dynamicMusic.ambiance.BackgroundFitMode;
import com.marianocarrizo.dynamicMusic.ambiance.EffectType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import com.marianocarrizo.dynamicMusic.service.PlaylistManager;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.util.Duration;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PlayerController {

    // Layout
    @FXML private VBox mainRoot;
    @FXML private Button btnMaximize;
    @FXML private VBox detailsPanel;
    @FXML private Label lblBackgroundType;
    @FXML private VBox assignmentsList;

    // Slider y label del volumen maestro global
    @FXML private Slider sldMasterVolume;
    @FXML private Label  lblMasterVolume;

    // Sliders de sensibilidad por mood
    @FXML private Slider sldSensPaused;
    @FXML private Slider sldSensNormal;
    @FXML private Slider sldSensRaged;
    @FXML private Slider sldSensAdvantage;

    // Labels de valor de sensibilidad por mood
    @FXML private Label lblSensPaused;
    @FXML private Label lblSensNormal;
    @FXML private Label lblSensRaged;
    @FXML private Label lblSensAdvantage;

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
    @FXML private ToggleButton btnPreviewLoop;
    @FXML private Button btnPreviewPlayPause;
    @FXML private Slider previewSlider;
    @FXML private Label lblPreviewCurrentTime;
    @FXML private Label lblPreviewTotalTime;
    @FXML private Label lblPreviewTrack;

    // Label general
    @FXML private Label lblNowPlaying;

    // T-10: TabPane de modos
    @FXML private TabPane modeTabPane;
    @FXML private Tab moodsTab;
    @FXML private Tab playlistsTab;

    // T-11: Panel de gestión de playlists
    @FXML private VBox playlistPanel;
    @FXML private ComboBox<String> cmbPlaylist;
    @FXML private ListView<String> lstPlaylistTracks;
    @FXML private Label lblTrackCounter;

    // T-11: Transporte del playlist
    @FXML private ToggleButton btnShuffle;
    @FXML private Button btnPrev;
    @FXML private Button btnPlaylistPlayPause;
    @FXML private Button btnNext;
    @FXML private ToggleButton btnPlaylistLoop;

    // T-11: Progreso del playlist
    @FXML private Label lblPlCurrentTime;
    @FXML private Slider plProgressSlider;
    @FXML private Label lblPlTotalTime;

    // Lista y búsqueda
    @FXML private TextField txtSearch;
    @FXML private TreeView<File> treeFiles;
    @FXML private ComboBox<TrackType> cmbTrackType;
    @FXML private ComboBox<EffectType> cmbEffect;
    @FXML private ComboBox<String> cmbScreen;
    @FXML private ComboBox<BackgroundFitMode> cmbFitMode;
    @FXML private Label lblBackgroundFile;

    // Segment loop controls (video-only, hidden by default via FXML)
    @FXML private VBox   segmentControls;
    @FXML private Button btnMarkStart;
    @FXML private Button btnMarkEnd;
    @FXML private Label  lblSegmentInfo;
    @FXML private Button btnClearSegment;

    private File rootFolder;

    private final MusicService musicService = new MusicService();
    private final SessionConfigManager manager = new SessionConfigManager();
    private final SessionConfig sessionConfig = new SessionConfig();
    private AmbianceStage ambianceStage;

    private boolean isDraggingSlider = false;
    private boolean isDraggingPreviewSlider = false;
    private boolean isDraggingPlaylistSlider = false;
    private boolean applyingSession = false;
    private boolean refreshingUI = false;
    private Duration pendingSegmentStart = null; // null = no start marked yet
    private TrackType currentActiveType = TrackType.NORMAL;

    private PlaylistManager playlistManager;
    private final Map<TrackType, Double> moodSensitivity = new java.util.EnumMap<>(TrackType.class);

    @FXML
    public void initialize() {
        updateButtons();

        if (cmbTrackType != null) {
            cmbTrackType.getItems().setAll(TrackType.values());
            cmbTrackType.setValue(TrackType.NORMAL);
        }

        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, oldVal, newVal) -> rebuildTree());
        }

        if (treeFiles != null) {
            treeFiles.setCellFactory(tv -> new TreeCell<>() {
                @Override
                protected void updateItem(File item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); return; }
                    setText(item.isDirectory() ? "📁 " + item.getName() : item.getName());
                }
            });
            treeFiles.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    TreeItem<File> selected = treeFiles.getSelectionModel().getSelectedItem();
                    if (selected != null && selected.getValue() != null
                            && !selected.getValue().isDirectory()) {
                        startPreview(selected.getValue());
                    }
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
        ambianceStage = new AmbianceStage();
        ambianceStage.setOnVideoReady(() -> Platform.runLater(() -> {
            if (btnMarkStart != null)    btnMarkStart.setDisable(false);
            if (btnClearSegment != null) btnClearSegment.setDisable(false);
            // btnMarkEnd permanece deshabilitado hasta que el usuario marque un inicio
            refreshSegmentLabel();
        }));
        musicService.setAmplitudeListener(amp -> ambianceStage.setAmplitude(amp));

        // Callback: el stage de ambiance notifica cuando el usuario suelta una imagen
        ambianceStage.setOnBackgroundDropped(path -> {
            sessionConfig.setBackgroundImagePath(path);
            sessionConfig.setLastImageFolderPath(new File(path).getParent());
            // El fondo soltado siempre es imagen/gif (video excluido de DnD); limpiar segmento
            pendingSegmentStart = null;
            sessionConfig.setVideoSegmentStartMs(null);
            sessionConfig.setVideoSegmentEndMs(null);
            manager.save(sessionConfig);
            Platform.runLater(() -> updateBackgroundLabel(path));
        });

        if (cmbEffect != null) {
            cmbEffect.getItems().setAll(EffectType.values());
            cmbEffect.setValue(EffectType.AURORA);
        }

        if (cmbFitMode != null) {
            cmbFitMode.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(BackgroundFitMode mode) {
                    if (mode == null) return "";
                    return switch (mode) {
                        case FILL    -> "Fill";
                        case FIT     -> "Fit";
                        case STRETCH -> "Stretch";
                        case CENTER  -> "Center";
                    };
                }
                @Override public BackgroundFitMode fromString(String s) {
                    return BackgroundFitMode.fromString(s != null ? s.toUpperCase() : null);
                }
            });
            cmbFitMode.getItems().setAll(BackgroundFitMode.values());
            cmbFitMode.setValue(BackgroundFitMode.FILL);
        }

        // Combo de selección de pantalla
        populateScreenCombo();
        Screen.getScreens().addListener((ListChangeListener<Screen>) change -> {
            populateScreenCombo();
            // Re-resolver desde la intención guardada, no desde el combo clampado.
            // Si el monitor recordado volvió a conectarse, la ambiance se mueve de vuelta
            // a él de inmediato; si sigue ausente, resolveScreen() aplica auto-fallback.
            int savedIntent = sessionConfig.getAmbianceScreenIndex();
            int comboTarget = savedIntent < 0 ? 0 : savedIntent + 1;
            boolean inRange = cmbScreen != null && comboTarget < cmbScreen.getItems().size();
            refreshingUI = true;
            try {
                if (cmbScreen != null) {
                    cmbScreen.getSelectionModel().select(inRange ? comboTarget : 0);
                }
            } finally {
                refreshingUI = false;
            }
            if (ambianceStage != null) {
                ambianceStage.applyScreen(savedIntent);
            }
        });

        for (TrackType t : TrackType.values()) moodSensitivity.put(t, 2.0);

        wireSensitivitySliders();
        wireMasterVolumeSlider();
        refreshAssignments();

        // T-12: PlaylistManager + wiring de callbacks
        playlistManager = new PlaylistManager();

        musicService.setOnPlaylistEnd(() -> Platform.runLater(() -> {
            playlistManager.next().ifPresentOrElse(
                f -> musicService.playPlaylistTrack(f),
                () -> { musicService.stopPlaylist(); refreshPlaylistUI(); }
            );
            refreshPlaylistUI();
        }));

        musicService.setPlaylistProgressListener((current, total) ->
            Platform.runLater(() -> {
                if (!isDraggingPlaylistSlider && total != null && !total.isUnknown()) {
                    if (plProgressSlider != null)
                        plProgressSlider.setValue(current.toMillis() / total.toMillis() * 100);
                    if (lblPlCurrentTime != null)
                        lblPlCurrentTime.setText(formatDuration(current));
                    if (lblPlTotalTime != null)
                        lblPlTotalTime.setText(formatDuration(total));
                }
            }));

        if (plProgressSlider != null) {
            plProgressSlider.setOnMousePressed(e -> isDraggingPlaylistSlider = true);
            plProgressSlider.setOnMouseReleased(e -> {
                isDraggingPlaylistSlider = false;
                musicService.seekPlaylist(plProgressSlider.getValue() / 100.0);
            });
        }

        if (cmbPlaylist != null) {
            cmbPlaylist.setOnAction(e -> {
                String name = cmbPlaylist.getValue();
                if (name == null) return;
                playlistManager.setActivePlaylist(name);
                sessionConfig.setActivePlaylistName(name);
                manager.save(sessionConfig);
                refreshPlaylistUI();
            });
        }

        if (lstPlaylistTracks != null) {
            lstPlaylistTracks.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().remove("list-cell-now-playing");
                    if (empty || item == null) { setText(null); return; }
                    setText(item);
                    if (musicService.isPlaylistPlaying()
                            && getIndex() == playlistManager.currentDisplayIndex()) {
                        getStyleClass().add("list-cell-now-playing");
                    }
                }
            });
            lstPlaylistTracks.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    int idx = lstPlaylistTracks.getSelectionModel().getSelectedIndex();
                    if (idx >= 0) {
                        playlistManager.startAt(idx).ifPresent(f -> {
                            musicService.playPlaylistTrack(f);
                            if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("⏸");
                            refreshPlaylistUI();
                        });
                    }
                }
            });
        }

        if (modeTabPane != null) {
            modeTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (applyingSession || newTab == null) return;
                boolean isPlaylist = newTab == playlistsTab;
                musicService.setPlaybackMode(isPlaylist
                    ? MusicService.PlaybackMode.PLAYLIST
                    : MusicService.PlaybackMode.MOOD);
                sessionConfig.setActiveMode(isPlaylist ? "playlist" : "mood");
                manager.save(sessionConfig);
                if (isPlaylist) refreshPlaylistUI();
            });
        }

        Platform.runLater(() -> {
            updateWindowMood(TrackType.NORMAL);
            ambianceStage.open();
        });
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
        if (ambianceStage != null) {
            ambianceStage.setMood(type);
            if (cmbEffect != null) cmbEffect.setValue(ambianceStage.getMoodEffect(type));
            double s = moodSensitivity.getOrDefault(type, 2.0);
            ambianceStage.setSensitivity(s);
        }
        updateButtons();
        setMoodButtonIcon("⏸"); // siempre arranca reproduciendo
        updatePreviewPanel(false);
        resetPreviewSlider();
    }

    @FXML
    private void onTogglePlayPause() {
        if (musicService.isPlaying()) {
            setMoodButtonIcon("▶"); // feedback inmediato — el audio baja en 1s
            musicService.fadeOutAndPause(null);
        } else {
            setMoodButtonIcon("⏸");
            musicService.fadeInAndPlay();
        }
    }

    @FXML
    private void onStop() {
        musicService.stop(); // cancela fade interno y para inmediatamente
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
        if (musicService.isPlaying()) musicService.fadeOutAndPause(null);
        if (musicService.isPlaylistPlaying()) musicService.playlistPause();
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
            lblPreviewTrack.setText("No active preview");
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
    private void onSetBackground() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select background for ambiance");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Background (images/video)",
                        "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.mp4", "*.avi"));

        // Abrir en el último directorio de imagen usado, si existe
        String lastImgDir = sessionConfig.getLastImageFolderPath();
        if (lastImgDir != null) {
            File dir = new File(lastImgDir);
            if (dir.isDirectory()) chooser.setInitialDirectory(dir);
        }

        File file = chooser.showOpenDialog(null);
        if (file == null) return;

        String path = file.getAbsolutePath();
        sessionConfig.setBackgroundImagePath(path);
        sessionConfig.setLastImageFolderPath(file.getParent());
        // Cualquier nuevo fondo borra la ventana de loop activa
        pendingSegmentStart = null;
        sessionConfig.setVideoSegmentStartMs(null);
        sessionConfig.setVideoSegmentEndMs(null);
        manager.save(sessionConfig);
        if (ambianceStage != null) ambianceStage.setBackground(path);
        updateBackgroundLabel(path);
    }

    @FXML
    private void onSelectEffect() {
        if (cmbEffect == null) return;
        EffectType effect = cmbEffect.getValue();
        if (effect == null) return;
        sessionConfig.setMoodEffect(currentActiveType, effect);
        manager.save(sessionConfig);
        if (ambianceStage != null) ambianceStage.setMoodEffect(currentActiveType, effect);
    }

    @FXML
    private void onSelectFitMode() {
        if (refreshingUI || cmbFitMode == null) return;
        BackgroundFitMode mode = cmbFitMode.getValue();
        if (mode == null) return;
        sessionConfig.setBackgroundFitMode(mode.name());
        manager.save(sessionConfig);
        if (ambianceStage != null) ambianceStage.setBackgroundFitMode(mode);
    }

    // --- Segment loop handlers ---

    @FXML
    private void onMarkStart() {
        if (ambianceStage == null) return;
        pendingSegmentStart = ambianceStage.getCurrentVideoTime();
        if (lblSegmentInfo != null)
            lblSegmentInfo.setText("Start " + formatSegmentTime(pendingSegmentStart) + " — mark end");
        if (btnMarkEnd != null) btnMarkEnd.setDisable(false);
    }

    @FXML
    private void onMarkEnd() {
        if (ambianceStage == null || pendingSegmentStart == null) {
            if (lblSegmentInfo != null) lblSegmentInfo.setText("Mark a start position first");
            return;
        }
        Duration end = ambianceStage.getCurrentVideoTime();
        Duration gap = end.subtract(pendingSegmentStart);
        // Piso de 0.5s: verificado empíricamente que loops sub-2s reproducen sin problemas
        if (end.lessThanOrEqualTo(pendingSegmentStart) || gap.lessThan(Duration.millis(500))) {
            if (lblSegmentInfo != null) lblSegmentInfo.setText("End must be at least 0.5s after start");
            return; // loop activo sin cambios; nada persistido
        }
        ambianceStage.setVideoSegment(pendingSegmentStart, end);
        sessionConfig.setVideoSegmentStartMs((long) pendingSegmentStart.toMillis());
        sessionConfig.setVideoSegmentEndMs((long) end.toMillis());
        manager.save(sessionConfig);
        if (lblSegmentInfo != null)
            lblSegmentInfo.setText("Loop: " + formatSegmentTime(pendingSegmentStart) + " – " + formatSegmentTime(end));
    }

    @FXML
    private void onClearSegment() {
        if (ambianceStage == null) return;
        ambianceStage.clearVideoSegment();
        pendingSegmentStart = null;
        sessionConfig.setVideoSegmentStartMs(null);
        sessionConfig.setVideoSegmentEndMs(null);
        manager.save(sessionConfig);
        if (lblSegmentInfo != null) lblSegmentInfo.setText("Full video");
        if (btnMarkEnd != null) btnMarkEnd.setDisable(true);
    }

    // --- Selección de pantalla de ambiance ---

    /**
     * Repopula el ComboBox de pantallas sin disparar onSelectScreen.
     * Conserva la selección previa si el índice sigue siendo válido.
     */
    private void populateScreenCombo() {
        if (cmbScreen == null) return;
        refreshingUI = true;
        try {
            int previousComboIndex = cmbScreen.getSelectionModel().getSelectedIndex();
            List<Screen> screens = Screen.getScreens();

            cmbScreen.getItems().clear();
            cmbScreen.getItems().add("Auto (recommended)");
            for (int i = 0; i < screens.size(); i++) {
                Screen s = screens.get(i);
                int w = (int) s.getBounds().getWidth();
                int h = (int) s.getBounds().getHeight();
                String tag = s.equals(Screen.getPrimary()) ? " (primary)" : "";
                cmbScreen.getItems().add("Screen " + (i + 1) + " — " + w + "x" + h + tag);
            }

            // Restaurar selección previa si sigue siendo válida; si no, seleccionar "Auto"
            if (previousComboIndex >= 0 && previousComboIndex < cmbScreen.getItems().size()) {
                cmbScreen.getSelectionModel().select(previousComboIndex);
            } else {
                cmbScreen.getSelectionModel().select(0);
            }
        } finally {
            refreshingUI = false;
        }
    }

    /**
     * Convierte el índice visual del ComboBox al índice de Screen (0-based) o -1 para Auto.
     * Índice 0 del combo → Auto (-1); índice N → pantalla N-1.
     */
    private int screenComboIndexToScreenIndex(int comboIndex) {
        return comboIndex <= 0 ? -1 : comboIndex - 1;
    }

    @FXML
    private void onSelectScreen() {
        if (refreshingUI || cmbScreen == null) return;
        int resolvedIndex = screenComboIndexToScreenIndex(cmbScreen.getSelectionModel().getSelectedIndex());
        sessionConfig.setAmbianceScreenIndex(resolvedIndex);
        manager.save(sessionConfig);
        if (ambianceStage != null) {
            ambianceStage.applyScreen(resolvedIndex);
        }
    }

    // --- Label de archivo de fondo ---

    /**
     * Actualiza el label con el nombre del archivo de fondo actual.
     * Muestra el placeholder si la ruta es null o el archivo no existe.
     */
    private void updateBackgroundLabel(String path) {
        if (lblBackgroundFile == null) return;
        if (path == null || !new File(path).exists()) {
            lblBackgroundFile.setText("No background set");
            if (lblBackgroundType != null) lblBackgroundType.setText("");
        } else {
            lblBackgroundFile.setText(new File(path).getName());
            if (lblBackgroundType != null) lblBackgroundType.setText(backgroundMediaType(path));
        }
        boolean isVideo = "Video".equals(backgroundMediaType(path));
        if (segmentControls != null) {
            segmentControls.setVisible(isVideo);
            segmentControls.setManaged(isVideo);
        }
        // Resetear habilitación; los botones de marca se re-habilitan en el callback onVideoReady.
        // Clear queda habilitado con cualquier fondo de video (REQ-04), sin esperar READY.
        if (btnMarkStart != null)    btnMarkStart.setDisable(true);
        if (btnMarkEnd != null)      btnMarkEnd.setDisable(true);
        if (btnClearSegment != null) btnClearSegment.setDisable(!isVideo);
        pendingSegmentStart = null;
        refreshSegmentLabel();
    }

    /**
     * Infiere el tipo de medio a partir de la extensión del archivo.
     * Retorna "Image", "GIF", "Video" o "" para extensiones desconocidas.
     */
    private String backgroundMediaType(String path) {
        if (path == null) return "";
        String lower = path.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return "";
        return switch (lower.substring(dot + 1)) {
            case "png", "jpg", "jpeg", "bmp" -> "Image";
            case "gif"                        -> "GIF";
            case "mp4", "avi"                 -> "Video";
            default                           -> "";
        };
    }

    // --- Slider de volumen maestro ---

    /** Persiste el valor actual del slider en la sesión. Solo se llama desde gestos del usuario. */
    private void persistMasterVolume() {
        double v = sldMasterVolume.getValue();
        sessionConfig.setMasterVolume(v);
        manager.save(sessionConfig);
    }

    /**
     * Conecta el slider de volumen maestro con el servicio de audio y la persistencia.
     * El listener de valor aplica el volumen en vivo pero nunca persiste;
     * la persistencia ocurre solo en los tres handlers de gesto del usuario.
     */
    private void wireMasterVolumeSlider() {
        if (sldMasterVolume == null) return;
        double initial = sessionConfig.getMasterVolume();
        sldMasterVolume.setValue(initial);
        if (lblMasterVolume != null)
            lblMasterVolume.setText(Math.round(initial * 100) + "%");

        // LIVE: aplica el volumen en tiempo real; nunca persiste aqui
        sldMasterVolume.valueProperty().addListener((obs, oldV, newV) -> {
            double v = newV.doubleValue();
            musicService.setMasterVolume(v);
            if (lblMasterVolume != null)
                lblMasterVolume.setText(Math.round(v * 100) + "%");
        });

        // Triple-trigger de persistencia (solo gestos del usuario)
        sldMasterVolume.setOnMouseReleased(e -> persistMasterVolume());
        sldMasterVolume.valueChangingProperty().addListener((o, was, is) -> {
            if (was && !is) persistMasterVolume();
        });
        sldMasterVolume.focusedProperty().addListener((o, had, has) -> {
            if (had && !has) persistMasterVolume();
        });
    }

    // --- Sliders de sensibilidad ---

    /**
     * Conecta los cuatro sliders de sensibilidad con el mapa en memoria y la persistencia.
     * Llamado desde initialize() después de pre-sembrar moodSensitivity con valores por defecto.
     */
    private void wireSensitivitySliders() {
        wireSingleSensitivitySlider(TrackType.PAUSED,     sldSensPaused,     lblSensPaused);
        wireSingleSensitivitySlider(TrackType.NORMAL,     sldSensNormal,     lblSensNormal);
        wireSingleSensitivitySlider(TrackType.RAGED,      sldSensRaged,      lblSensRaged);
        wireSingleSensitivitySlider(TrackType.ADVANTAGE,  sldSensAdvantage,  lblSensAdvantage);
    }

    private void wireSingleSensitivitySlider(TrackType mood, Slider slider, Label valueLabel) {
        if (slider == null) return;

        // Valor inicial desde el mapa en memoria (ya pre-sembrado con 2.0 en initialize)
        double initial = moodSensitivity.getOrDefault(mood, 2.0);
        slider.setValue(initial);
        if (valueLabel != null) valueLabel.setText(String.format("%.1fx", initial));

        // Listener de valor: actualiza mapa y label en tiempo real
        slider.valueProperty().addListener((obs, oldV, newV) -> {
            double rounded = Math.round(newV.doubleValue() * 10.0) / 10.0;
            moodSensitivity.put(mood, rounded);
            if (valueLabel != null) valueLabel.setText(String.format("%.1fx", rounded));
            if (mood == currentActiveType && ambianceStage != null) {
                ambianceStage.setSensitivity(rounded);
            }
            // Persistencia en false-edge de valueChanging (cubre flechas de teclado)
        });

        // Persistencia al soltar el mouse
        slider.setOnMouseReleased(e -> {
            double rounded = Math.round(slider.getValue() * 10.0) / 10.0;
            sessionConfig.setMoodSensitivity(mood, rounded);
            manager.save(sessionConfig);
        });

        // Persistencia al finalizar cambio por teclado (false-edge de valueChanging)
        slider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (wasChanging && !isChanging) {
                double rounded = Math.round(slider.getValue() * 10.0) / 10.0;
                sessionConfig.setMoodSensitivity(mood, rounded);
                manager.save(sessionConfig);
            }
        });

        // Persistencia al perder el foco (cubre teclas en los extremos del rango)
        slider.focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
            if (hadFocus && !hasFocus) {
                double rounded = Math.round(slider.getValue() * 10.0) / 10.0;
                sessionConfig.setMoodSensitivity(mood, rounded);
                manager.save(sessionConfig);
            }
        });
    }

    /**
     * Aplica el valor del mapa en memoria al slider y label indicados.
     * Usado desde applySession (Platform.runLater) para restaurar la UI.
     */
    private void updateSensitivitySlider(TrackType mood, Slider slider, Label valueLabel) {
        if (slider == null) return;
        double v = moodSensitivity.getOrDefault(mood, 2.0);
        slider.setValue(v);
        if (valueLabel != null) valueLabel.setText(String.format("%.1fx", v));
    }

    // --- Lista de asignaciones ---

    /**
     * Reconstruye la lista de asignaciones del panel de detalles.
     * Muestra una fila por TrackType con el nombre del archivo asignado o "(none)".
     */
    private void refreshAssignments() {
        if (assignmentsList == null) return;
        assignmentsList.getChildren().clear();
        Map<TrackType, String> assignments = sessionConfig.getAssignments();
        for (TrackType t : TrackType.values()) {
            String path = assignments.get(t);
            String display = (path != null && !path.isEmpty())
                ? new File(path).getName()
                : "(none)";
            Label row = new Label(t.name() + "  →  " + display);
            row.getStyleClass().add("lbl-preview-track");
            row.setMaxWidth(Double.MAX_VALUE);
            assignmentsList.getChildren().add(row);
        }
    }

    @FXML
    private void onAssignTrack() {
        if (treeFiles == null || cmbTrackType == null) return;
        TreeItem<File> selectedItem = treeFiles.getSelectionModel().getSelectedItem();
        if (selectedItem == null) return;
        File selected = selectedItem.getValue();
        TrackType type = cmbTrackType.getValue();
        if (selected != null && !selected.isDirectory() && type != null) {
            musicService.loadTrack(type, selected);
            sessionConfig.put(type, selected.getAbsolutePath());
            manager.save(sessionConfig);
            refreshAssignments();
        }
    }

    // --- T-13: CRUD de playlists ---

    @FXML private void onNewPlaylist() {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("New Playlist");
        d.setHeaderText(null);
        d.setContentText("Name:");
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (playlistManager.createPlaylist(name) == null) {
                new Alert(Alert.AlertType.WARNING, "Name already exists or is blank.").showAndWait();
                return;
            }
            refreshPlaylistUI();
            persistPlaylists();
        });
    }

    @FXML private void onRenamePlaylist() {
        String current = cmbPlaylist.getValue();
        if (current == null) return;
        TextInputDialog d = new TextInputDialog(current);
        d.setTitle("Rename");
        d.setHeaderText(null);
        d.setContentText("New name:");
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (!playlistManager.renamePlaylist(current, name)) {
                new Alert(Alert.AlertType.WARNING, "Name already exists or is blank.").showAndWait();
                return;
            }
            refreshPlaylistUI();
            persistPlaylists();
        });
    }

    @FXML private void onDeletePlaylist() {
        String name = cmbPlaylist.getValue();
        if (name == null) return;
        boolean isEmpty = playlistManager.getPlaylists().stream()
            .filter(p -> p.getName().equals(name))
            .findFirst()
            .map(p -> p.getPaths().isEmpty())
            .orElse(true);
        if (!isEmpty) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete playlist \"" + name + "\" and all its tracks?");
            if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        }
        playlistManager.deletePlaylist(name);
        refreshPlaylistUI();
        persistPlaylists();
    }

    @FXML private void onAddToPlaylist() {
        String pname = cmbPlaylist.getValue();
        if (pname == null || treeFiles == null) return;
        TreeItem<File> sel = treeFiles.getSelectionModel().getSelectedItem();
        if (sel == null || sel.getValue() == null || sel.getValue().isDirectory()) return;
        playlistManager.addTrack(pname, sel.getValue());
        refreshPlaylistUI();
        persistPlaylists();
    }

    @FXML private void onRemoveTrack() {
        String pname = cmbPlaylist.getValue();
        if (pname == null) return;
        int idx = lstPlaylistTracks.getSelectionModel().getSelectedIndex();
        if (idx < 0) return;
        playlistManager.removeTrack(pname, idx);
        refreshPlaylistUI();
        persistPlaylists();
    }

    @FXML private void onMoveUp() {
        String pname = cmbPlaylist.getValue();
        if (pname == null) return;
        int idx = lstPlaylistTracks.getSelectionModel().getSelectedIndex();
        if (idx <= 0) return;
        playlistManager.reorderTrack(pname, idx, idx - 1);
        refreshPlaylistUI();
        lstPlaylistTracks.getSelectionModel().select(idx - 1);
        persistPlaylists();
    }

    @FXML private void onMoveDown() {
        String pname = cmbPlaylist.getValue();
        if (pname == null) return;
        int idx = lstPlaylistTracks.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= lstPlaylistTracks.getItems().size() - 1) return;
        playlistManager.reorderTrack(pname, idx, idx + 1);
        refreshPlaylistUI();
        lstPlaylistTracks.getSelectionModel().select(idx + 1);
        persistPlaylists();
    }

    private void persistPlaylists() {
        sessionConfig.setPlaylists(playlistManager.getPlaylists());
        manager.save(sessionConfig);
    }

    // --- T-14: Transporte y refresh del playlist ---

    @FXML private void onPlaylistPlayPause() {
        if (musicService.isPlaylistPlaying()) {
            musicService.playlistPause();
            if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("▶");
        } else {
            playlistManager.getCurrentTrack().ifPresent(f -> {
                musicService.playPlaylistTrack(f);
                if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("⏸");
            });
        }
    }

    @FXML private void onPrev() {
        playlistManager.prev().ifPresent(f -> {
            musicService.playPlaylistTrack(f);
            if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("⏸");
        });
        refreshPlaylistUI();
    }

    @FXML private void onNext() {
        playlistManager.next().ifPresentOrElse(f -> {
            musicService.playPlaylistTrack(f);
            if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("⏸");
        }, () -> {
            musicService.stopPlaylist();
            if (btnPlaylistPlayPause != null) btnPlaylistPlayPause.setText("▶");
        });
        refreshPlaylistUI();
    }

    @FXML private void onToggleShuffle() {
        playlistManager.setShuffle(btnShuffle != null && btnShuffle.isSelected());
        refreshPlaylistUI();
    }

    @FXML private void onTogglePlaylistLoop() {
        playlistManager.setLoop(btnPlaylistLoop != null && btnPlaylistLoop.isSelected());
    }

    private void refreshPlaylistUI() {
        if (cmbPlaylist == null || refreshingUI) return;
        refreshingUI = true;
        try {
        // Repopulate playlist ComboBox
        String current = cmbPlaylist.getValue();
        cmbPlaylist.getItems().setAll(
            playlistManager.getPlaylists().stream()
                .map(p -> p.getName())
                .toList()
        );
        if (current != null && cmbPlaylist.getItems().contains(current))
            cmbPlaylist.setValue(current);

        // Repopulate track ListView with basenames
        if (lstPlaylistTracks != null) {
            lstPlaylistTracks.getItems().setAll(
                playlistManager.getActivePaths().stream()
                    .map(path -> new File(path).getName())
                    .toList()
            );

            // Highlight current track
            if (playlistManager.size() > 0) {
                lstPlaylistTracks.getSelectionModel().select(playlistManager.currentDisplayIndex());
                lstPlaylistTracks.refresh();
            }
        }

        // Update counter
        if (lblTrackCounter != null)
            lblTrackCounter.setText(playlistManager.positionLabel());
        } finally {
            refreshingUI = false;
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

        // Restore per-mood effect preferences
        for (TrackType type : TrackType.values()) {
            EffectType effect = cfg.getMoodEffect(type);
            sessionConfig.setMoodEffect(type, effect);
            if (ambianceStage != null) ambianceStage.setMoodEffect(type, effect);
        }

        // Restaurar ruta de imagen de fondo global
        String bgPath = cfg.getBackgroundImagePath();
        if (bgPath != null && new File(bgPath).exists()) {
            // La ruta existe: persistir y aplicar normalmente.
            sessionConfig.setBackgroundImagePath(bgPath);
        } else if (bgPath != null) {
            // La ruta fue guardada pero el archivo ya no existe: limpiar para evitar
            // que session.json vuelva a persistir un path roto en la próxima sesión.
            sessionConfig.setBackgroundImagePath(null);
            bgPath = null;
        }
        String lastImgDir = cfg.getLastImageFolderPath();
        if (lastImgDir != null) {
            sessionConfig.setLastImageFolderPath(lastImgDir);
        }
        // Reflejar valores de segmento en sessionConfig local para que saves posteriores los round-tripeen
        Long segStart = cfg.getVideoSegmentStartMs();
        Long segEnd   = cfg.getVideoSegmentEndMs();
        sessionConfig.setVideoSegmentStartMs(segStart);
        sessionConfig.setVideoSegmentEndMs(segEnd);

        final String bgPathFinal = bgPath;
        Platform.runLater(() -> {
            if (ambianceStage != null && bgPathFinal != null) {
                boolean isVideo = "Video".equals(backgroundMediaType(bgPathFinal));
                if (isVideo && segStart != null && segEnd != null) {
                    // Establecer el segmento ANTES de setBackground para que setVideo() lo reciba en READY
                    ambianceStage.setVideoSegment(Duration.millis(segStart), Duration.millis(segEnd));
                } else if (!isVideo) {
                    // Guardia: segmento obsoleto de una sesión anterior donde cambió el tipo de fondo
                    sessionConfig.setVideoSegmentStartMs(null);
                    sessionConfig.setVideoSegmentEndMs(null);
                    if (ambianceStage != null) {
                        ambianceStage.clearVideoSegment();
                    }
                }
                ambianceStage.setBackground(bgPathFinal);   // dispara setVideo → aplica campos en READY
                updateBackgroundLabel(bgPathFinal);          // alterna visibilidad de segmentControls
            } else {
                updateBackgroundLabel(null);
            }
        });

        // Restaurar índice de pantalla de ambiance
        int screenIdx = cfg.getAmbianceScreenIndex();
        sessionConfig.setAmbianceScreenIndex(screenIdx);
        Platform.runLater(() -> {
            populateScreenCombo();
            // Seleccionar el ítem correcto en el combo (índice -1 → posición 0 "Auto")
            if (cmbScreen != null) {
                int comboPos = screenIdx < 0 ? 0 : screenIdx + 1;
                // Si el índice recordado está fuera del rango disponible, mostrar "Auto"
                // sin sobrescribir el índice guardado — la intención se conserva.
                int safePos = comboPos < cmbScreen.getItems().size() ? comboPos : 0;
                refreshingUI = true;
                try {
                    cmbScreen.getSelectionModel().select(safePos);
                } finally {
                    refreshingUI = false;
                }
            }
            if (ambianceStage != null) {
                ambianceStage.applyScreen(screenIdx);
            }
        });

        // Restaurar modo de ajuste del fondo
        BackgroundFitMode fit = BackgroundFitMode.fromString(cfg.getBackgroundFitMode());
        sessionConfig.setBackgroundFitMode(fit.name());
        Platform.runLater(() -> {
            refreshingUI = true;
            try {
                if (cmbFitMode != null) cmbFitMode.setValue(fit);
            } finally {
                refreshingUI = false;
            }
            if (ambianceStage != null) ambianceStage.setBackgroundFitMode(fit);
        });

        String lastPath = cfg.getLastFolderPath();
        if (lastPath != null) {
            File last = new File(lastPath);
            if (last.isDirectory()) {
                rootFolder = last;
                sessionConfig.setLastFolderPath(lastPath);
                Platform.runLater(this::rebuildTree);
            }
        }

        // T-15: Restore playlists
        playlistManager.setPlaylists(cfg.getPlaylists());
        refreshPlaylistUI();

        // T-15: Restore active mode
        String savedMode = cfg.getActiveMode();
        if ("playlist".equalsIgnoreCase(savedMode) && modeTabPane != null && playlistsTab != null) {
            applyingSession = true;
            modeTabPane.getSelectionModel().select(playlistsTab);
            String savedPl = cfg.getActivePlaylistName();
            if (savedPl != null) {
                playlistManager.setActivePlaylist(savedPl);
                if (cmbPlaylist != null) cmbPlaylist.setValue(savedPl);
            }
            refreshPlaylistUI();
            applyingSession = false;
        }

        // Restaurar sensibilidades por mood desde la sesión guardada
        for (TrackType t : TrackType.values()) {
            double v = cfg.getMoodSensitivity(t);
            moodSensitivity.put(t, v);
            sessionConfig.setMoodSensitivity(t, v);
        }
        // Aplicar en UI (puede ejecutarse antes de show(); Platform.runLater es seguro)
        Platform.runLater(() -> {
            updateSensitivitySlider(TrackType.PAUSED,     sldSensPaused,     lblSensPaused);
            updateSensitivitySlider(TrackType.NORMAL,     sldSensNormal,     lblSensNormal);
            updateSensitivitySlider(TrackType.RAGED,      sldSensRaged,      lblSensRaged);
            updateSensitivitySlider(TrackType.ADVANTAGE,  sldSensAdvantage,  lblSensAdvantage);

            // Restaurar volumen maestro desde sesión
            double mv = cfg.getMasterVolume();
            sessionConfig.setMasterVolume(mv);
            musicService.setMasterVolume(mv);
            if (sldMasterVolume != null) sldMasterVolume.setValue(mv); // listener fires -> idempotent
            if (lblMasterVolume != null)
                lblMasterVolume.setText(Math.round(mv * 100) + "%");

            refreshAssignments();
        });
    }

    // --- Carpeta y búsqueda ---

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select music folder");
        String lastPath = sessionConfig.getLastFolderPath();
        if (lastPath != null) {
            File last = new File(lastPath);
            if (last.isDirectory()) chooser.setInitialDirectory(last);
        }
        File folder = chooser.showDialog(null);
        if (folder == null || !folder.isDirectory()) return;
        rootFolder = folder;
        sessionConfig.setLastFolderPath(folder.getAbsolutePath());
        manager.save(sessionConfig);
        if (txtSearch != null) txtSearch.clear();
        rebuildTree();
    }

    private void rebuildTree() {
        if (treeFiles == null || rootFolder == null) return;
        String filter = txtSearch != null ? txtSearch.getText().toLowerCase() : "";
        TreeItem<File> root = new TreeItem<>(rootFolder);
        root.setExpanded(true);
        populateTree(root, rootFolder, filter);
        treeFiles.setRoot(root);
    }

    private void populateTree(TreeItem<File> parent, File dir, String filter) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::isFile)  // carpetas primero
                              .thenComparing(f -> f.getName().toLowerCase()));
        for (File entry : entries) {
            if (entry.isDirectory()) {
                TreeItem<File> dirItem = new TreeItem<>(entry);
                populateTree(dirItem, entry, filter);
                if (!dirItem.getChildren().isEmpty()) {
                    dirItem.setExpanded(false);
                    parent.getChildren().add(dirItem);
                }
            } else if (isAudioFile(entry)) {
                if (filter.isEmpty() || entry.getName().toLowerCase().contains(filter)) {
                    parent.getChildren().add(new TreeItem<>(entry));
                }
            }
        }
    }

    private boolean isAudioFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac");
    }

    // --- Controles de ventana ---

    @FXML
    private void onToggleLoop() {
        if (btnLoopIcon != null) musicService.setLoop(btnLoopIcon.isSelected());
    }

    @FXML
    private void onTogglePreviewLoop() {
        if (btnPreviewLoop != null) musicService.setPreviewLoop(btnPreviewLoop.isSelected());
    }

    @FXML
    public void onMaximizeRestoreWindow(ActionEvent event) {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        stage.setMaximized(!stage.isMaximized());
        btnMaximize.setText(stage.isMaximized() ? "❐" : "▢");
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

    /**
     * Formatea un Duration como m:ss para el label del segmento (por ejemplo, "0:12", "1:05").
     * Null-safe: retorna "0:00" para entrada null.
     */
    private String formatSegmentTime(Duration d) {
        if (d == null) return "0:00";
        int totalSeconds = (int) d.toSeconds();
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }

    /**
     * Refresca lblSegmentInfo a partir de los valores de sesión persistidos.
     * Muestra "Full video" cuando los campos ms son null, o "Loop: m:ss – m:ss" en caso contrario.
     */
    private void refreshSegmentLabel() {
        if (lblSegmentInfo == null) return;
        Long startMs = sessionConfig.getVideoSegmentStartMs();
        Long endMs   = sessionConfig.getVideoSegmentEndMs();
        if (startMs == null || endMs == null) {
            lblSegmentInfo.setText("Full video");
        } else {
            lblSegmentInfo.setText("Loop: "
                + formatSegmentTime(Duration.millis(startMs))
                + " – "
                + formatSegmentTime(Duration.millis(endMs)));
        }
    }
}
