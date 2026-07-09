package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.ambiance.AmbianceStage;
import com.marianocarrizo.dynamicMusic.ambiance.EffectType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import com.marianocarrizo.dynamicMusic.service.PlaylistManager;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
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
    @FXML private VBox leftPanel;
    @FXML private VBox rightPanel;
    @FXML private Separator panelSeparator;
    @FXML private Button btnExpandPanel;

    private boolean leftPanelExpanded = false;

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
    @FXML private Label lblBackgroundFile;

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
        musicService.setAmplitudeListener(amp -> ambianceStage.setAmplitude(amp));

        // Callback: el stage de ambiance notifica cuando el usuario suelta una imagen
        ambianceStage.setOnBackgroundDropped(path -> {
            sessionConfig.setBackgroundImagePath(path);
            sessionConfig.setLastImageFolderPath(new File(path).getParent());
            manager.save(sessionConfig);
            Platform.runLater(() -> updateBackgroundLabel(path));
        });

        if (cmbEffect != null) {
            cmbEffect.getItems().setAll(EffectType.values());
            cmbEffect.setValue(EffectType.AURORA);
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
        } else {
            lblBackgroundFile.setText(new File(path).getName());
        }
    }

    @FXML
    private void onOpenSensitivityConfig() {
        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.setTitle("Sensitivity per Mood");
        dialog.initStyle(javafx.stage.StageStyle.UTILITY);
        dialog.setResizable(false);

        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(14);
        vbox.setPadding(new javafx.geometry.Insets(20));

        for (TrackType mood : TrackType.values()) {
            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(12);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            javafx.scene.control.Label name = new javafx.scene.control.Label(mood.name());
            name.setMinWidth(90);

            javafx.scene.control.Slider slider = new javafx.scene.control.Slider(0.5, 8.0,
                    moodSensitivity.getOrDefault(mood, 2.0));
            slider.setPrefWidth(200);
            slider.setShowTickMarks(true);
            slider.setMajorTickUnit(1.0);
            slider.setBlockIncrement(0.5);

            javafx.scene.control.Label valLabel = new javafx.scene.control.Label(
                    String.format("%.1fx", slider.getValue()));
            valLabel.setMinWidth(40);

            slider.valueProperty().addListener((obs, oldV, newV) -> {
                double s = Math.round(newV.doubleValue() * 10.0) / 10.0;
                moodSensitivity.put(mood, s);
                valLabel.setText(s + "x");
                if (mood == currentActiveType && ambianceStage != null)
                    ambianceStage.setSensitivity(s);
            });

            row.getChildren().addAll(name, slider, valLabel);
            vbox.getChildren().add(row);
        }

        dialog.setScene(new javafx.scene.Scene(vbox));
        dialog.show();
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
        final String bgPathFinal = bgPath;
        Platform.runLater(() -> {
            if (ambianceStage != null && bgPathFinal != null) {
                ambianceStage.setBackground(bgPathFinal);
                updateBackgroundLabel(bgPathFinal);
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
    }

    // --- Carpeta y búsqueda ---

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Seleccionar carpeta de música");
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
    private void onToggleExpandPanel() {
        leftPanelExpanded = !leftPanelExpanded;
        if (rightPanel != null)    rightPanel.setVisible(!leftPanelExpanded);
        if (rightPanel != null)    rightPanel.setManaged(!leftPanelExpanded);
        if (panelSeparator != null) panelSeparator.setVisible(!leftPanelExpanded);
        if (panelSeparator != null) panelSeparator.setManaged(!leftPanelExpanded);
        if (leftPanel != null) {
            leftPanel.setMaxWidth(leftPanelExpanded ? Double.MAX_VALUE : 280);
            javafx.scene.layout.HBox.setHgrow(leftPanel,
                leftPanelExpanded ? javafx.scene.layout.Priority.ALWAYS : javafx.scene.layout.Priority.NEVER);
        }
        if (btnExpandPanel != null) btnExpandPanel.setText(leftPanelExpanded ? "◱" : "⛶");
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
