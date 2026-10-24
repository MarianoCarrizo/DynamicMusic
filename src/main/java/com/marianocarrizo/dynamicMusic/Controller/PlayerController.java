package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.ambiance.AmbianceStage;
import com.marianocarrizo.dynamicMusic.ambiance.AuraBridge;
import com.marianocarrizo.dynamicMusic.ambiance.BackgroundFitMode;
import com.marianocarrizo.dynamicMusic.ambiance.BoomerangBaker;
import com.marianocarrizo.dynamicMusic.ambiance.YoutubePicker;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.model.BackgroundConfig;
import com.marianocarrizo.dynamicMusic.model.PlaylistItem;
import com.marianocarrizo.dynamicMusic.model.Scene;
import com.marianocarrizo.dynamicMusic.service.MusicService;
import com.marianocarrizo.dynamicMusic.service.PlaylistManager;
import com.marianocarrizo.dynamicMusic.service.TrackLibrary;
import com.marianocarrizo.dynamicMusic.service.YouTubeDownloader;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerController implements SceneHost {

    // Layout
    @FXML private VBox mainRoot;
    @FXML private Button btnMaximize;

    // Scene bar
    @FXML private ComboBox<Scene> cmbScene;
    // Main action toggles the ambiance window; its menu lists the screens
    @FXML private SplitMenuButton btnToggleAmbiance;

    // Slider y label del volumen maestro global
    @FXML private Slider sldMasterVolume;
    @FXML private Label  lblMasterVolume;

    // Botones (tarjetas) de mood
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

    // Panel de preescucha (visible solo mientras hay una preescucha activa)
    @FXML private VBox previewPanel;
    @FXML private ToggleButton btnPreviewLoop;
    @FXML private Button btnPreviewPlayPause;
    @FXML private Slider previewSlider;
    @FXML private Label lblPreviewCurrentTime;
    @FXML private Label lblPreviewTotalTime;
    @FXML private Label lblPreviewTrack;

    // Caption + nombre de la pista del mood activo
    @FXML private Label lblNowPlayingMood;
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

    // YouTube URL input
    @FXML private TextField txtYoutubeUrl;
    @FXML private Button btnAddYoutube;
    @FXML private Button btnBrowseYoutube;
    @FXML private Label lblYoutubeError;

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    private File rootFolder;

    private final MusicService musicService = new MusicService();
    private final SessionConfigManager manager = new SessionConfigManager();
    private final SessionConfig sessionConfig = new SessionConfig();
    private final YouTubeDownloader youTubeDownloader = new YouTubeDownloader();
    private AmbianceStage ambianceStage;
    private SceneEditorController sceneEditor; // created lazily, single instance

    private boolean isDraggingSlider = false;
    private boolean isDraggingPreviewSlider = false;
    private boolean isDraggingPlaylistSlider = false;
    private boolean applyingSession = false;
    private boolean refreshingUI = false;
    private TrackType currentActiveType = TrackType.NORMAL;

    // Boomerang state (live ambiance)
    private final BoomerangBaker boomerangBaker = new BoomerangBaker();
    private boolean ffmpegAvailable = false;
    private String bakeGuardSrc;   // snapshot en el momento del bake para la guardia de stale
    private long   bakeGuardStart;
    private long   bakeGuardEnd;

    // File IO off the FX thread (pinning yt-cache tracks into the library)
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "track-io");
        t.setDaemon(true);
        return t;
    });

    // Debounces saves coming from sliders and color pickers in the scene editor
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(400));

    // Path requested for each mood slot of the active scene; avoids reloading an unchanged track on scene switch
    private final Map<TrackType, String> loadedPaths = new EnumMap<>(TrackType.class);

    // Mood card widgets (the card buttons keep their fx:ids, handlers and shortcuts)
    private final Map<TrackType, Button> moodButtons = new EnumMap<>(TrackType.class);
    private final Map<TrackType, MoodCard> moodCards = new EnumMap<>(TrackType.class);

    private static final class MoodCard {
        final Label track = new Label();
        final Circle dot = new Circle(5);
        final Label preset = new Label();
    }

    // Screen menu (inside btnToggleAmbiance): item 0 = Auto, item N = screen N-1
    private final ToggleGroup screenGroup = new ToggleGroup();

    private PlaylistManager playlistManager;

    @FXML
    public void initialize() {
        buildMoodCards();
        refreshCards();
        updateButtons();
        updateNowPlayingCaption();

        saveDebounce.setOnFinished(e -> manager.save(sessionConfig));

        if (cmbScene != null) {
            cmbScene.setConverter(new StringConverter<>() {
                @Override public String toString(Scene s) { return s == null ? "" : s.getName(); }
                @Override public Scene fromString(String s) { return null; }
            });
            cmbScene.setOnAction(e -> {
                if (refreshingUI) return;
                Scene chosen = cmbScene.getValue();
                if (chosen == null || chosen.getId().equals(sessionConfig.getActiveScene().getId())) return;
                switchScene(chosen);
            });
            refreshSceneCombo();
        }

        if (txtSearch != null) {
            txtSearch.textProperty().addListener((obs, oldVal, newVal) -> rebuildTree());
        }

        if (treeFiles != null) {
            treeFiles.setCellFactory(tv -> new LibraryCell());
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
        // El live ambiance ya no expone controles de segmento; no hay setOnVideoReady en la UI
        musicService.setAmplitudeListener(amp -> ambianceStage.setAmplitude(amp));
        ambianceStage.setOnVisibilityChanged(this::updateAmbianceToggle);

        // Callback: el stage de ambiance notifica cuando el usuario suelta una imagen.
        // The drop replaces the background of the ACTIVE scene.
        ambianceStage.setOnBackgroundDropped(path -> {
            BackgroundConfig bg = sessionConfig.getActiveScene().getBackground();
            bg.setPath(path);
            sessionConfig.setLastImageFolderPath(new File(path).getParent());
            // DnD only accepts images/GIFs; clear any saved video state from a prior background
            bg.setSegmentStartMs(null);
            bg.setSegmentEndMs(null);
            bg.setBoomerangEnabled(false);
            manager.save(sessionConfig);
            Platform.runLater(() -> {
                if (sceneEditor != null) sceneEditor.refreshBackground();
            });
        });

        // Menú de selección de pantalla (dentro del botón de ambiance)
        populateScreenMenu();
        Screen.getScreens().addListener((ListChangeListener<Screen>) change -> {
            populateScreenMenu();
            // Re-resolver desde la intención guardada, no desde el menú clampado.
            // Si el monitor recordado volvió a conectarse, la ambiance se mueve de vuelta
            // a él de inmediato; si sigue ausente, resolveScreen() aplica auto-fallback.
            int savedIntent = sessionConfig.getAmbianceScreenIndex();
            int menuTarget = savedIntent < 0 ? 0 : savedIntent + 1;
            boolean inRange = menuTarget < screenItemCount();
            selectScreenItem(inRange ? menuTarget : 0);
            if (ambianceStage != null) {
                ambianceStage.applyScreen(savedIntent);
            }
        });

        wireMasterVolumeSlider();

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

    // --- Tarjetas de mood ---

    private void buildMoodCards() {
        bindCard(TrackType.PAUSED,    btnPaused,    1);
        bindCard(TrackType.NORMAL,    btnNormal,    2);
        bindCard(TrackType.RAGED,     btnRaged,     3);
        bindCard(TrackType.ADVANTAGE, btnAdvantage, 4);
    }

    /** Turns a mood button into a three-line card and makes it a drop target for tracks. */
    private void bindCard(TrackType type, Button button, int number) {
        if (button == null) return;
        MoodCard card = new MoodCard();

        Label title = new Label("[" + number + "] " + type.name());
        title.getStyleClass().add("card-title");
        card.track.getStyleClass().add("card-track");
        card.track.setMaxWidth(190);
        card.track.setAlignment(Pos.CENTER);
        card.preset.getStyleClass().add("card-aura");
        HBox auraRow = new HBox(5, card.dot, card.preset);
        auraRow.setAlignment(Pos.CENTER);

        VBox box = new VBox(4, title, card.track, auraRow);
        box.setAlignment(Pos.CENTER);
        button.setText(null);
        button.setGraphic(box);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setMinWidth(0);

        button.setOnDragOver(e -> {
            if (audioFileIn(e.getDragboard()) != null) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        button.setOnDragEntered(e -> {
            if (audioFileIn(e.getDragboard()) != null) button.pseudoClassStateChanged(DROP_TARGET, true);
        });
        button.setOnDragExited(e -> button.pseudoClassStateChanged(DROP_TARGET, false));
        button.setOnDragDropped(e -> {
            File f = audioFileIn(e.getDragboard());
            if (f != null) assignTrack(type, f);
            e.setDropCompleted(f != null);
            e.consume();
        });

        moodButtons.put(type, button);
        moodCards.put(type, card);
    }

    /** First supported audio file carried by the dragboard (tree drag or external drop), or null. */
    private File audioFileIn(Dragboard db) {
        if (db == null || !db.hasFiles()) return null;
        for (File f : db.getFiles()) {
            if (f.isFile() && isAudioFile(f)) return f;
        }
        return null;
    }

    /** Refreshes track name and aura preset/dot of every card from the active scene. */
    private void refreshCards() {
        Scene scene = sessionConfig.getActiveScene();
        Map<TrackType, String> assignments = scene.getAssignments();
        for (TrackType type : TrackType.values()) {
            MoodCard card = moodCards.get(type);
            if (card == null) continue;
            String path = assignments.get(type);
            card.track.getStyleClass().remove("card-track-empty");
            if (path == null || path.isEmpty()) {
                card.track.setText("Drop a track here");
                card.track.getStyleClass().add("card-track-empty");
            } else {
                File f = new File(path);
                card.track.setText(stripExtension(f.getName()) + (f.isFile() ? "" : " (missing)"));
            }
            AuraConfig aura = scene.getAura(AuraSlot.of(type));
            card.dot.setFill(Color.web(aura.getPrimaryColor()));
            card.preset.setText(capitalize(aura.getPreset().name()));
        }
    }

    private void updateNowPlayingCaption() {
        if (lblNowPlayingMood != null)
            lblNowPlayingMood.setText("NOW PLAYING · " + currentActiveType.name());
    }

    // --- Controles de mood ---

    @FXML private void onSwitchToPaused()   { switchTrack(TrackType.PAUSED); }
    @FXML private void onSwitchToNormal()   { switchTrack(TrackType.NORMAL); }
    @FXML private void onSwitchToRaged()    { switchTrack(TrackType.RAGED); }
    @FXML private void onSwitchToAdvantage(){ switchTrack(TrackType.ADVANTAGE); }

    public void switchTrack(TrackType type) {
        if (type == null || !musicService.isLoaded(type)) return;
        if (type == currentActiveType && musicService.getCurrentTrack() == type) return;
        musicService.switchTrack(type);
        this.currentActiveType = type;
        updateWindowMood(type);
        updateNowPlayingCaption();
        if (ambianceStage != null) {
            ambianceStage.setMood(type);
            // The stage has ONE sensitivity: take the one of the mood we are entering
            AuraConfig aura = sessionConfig.getActiveScene().getAura(AuraSlot.of(type));
            ambianceStage.setSensitivity(AuraBridge.sensitivityFor(aura));
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

    /** The preview strip is only shown (and takes space) while a preview is active. */
    private void updatePreviewPanel(boolean active) {
        if (previewPanel != null) {
            previewPanel.setVisible(active);
            previewPanel.setManaged(active);
        }
        if (btnPreviewPlayPause != null) btnPreviewPlayPause.setDisable(!active);
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
        for (TrackType t : TrackType.values()) {
            Button b = moodButtons.get(t);
            if (b == null) continue;
            // Empty cards stay enabled so they can receive drops; the click is a no-op (switchTrack guards)
            b.getStyleClass().remove("btn-phase-empty");
            b.getStyleClass().remove("btn-phase-active");
            if (!musicService.isLoaded(t)) b.getStyleClass().add("btn-phase-empty");
        }

        if (currentActiveType != null && musicService.isLoaded(currentActiveType)) {
            Button active = moodButtons.get(currentActiveType);
            if (active != null) active.getStyleClass().add("btn-phase-active");
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

    // --- Escenas ---

    private void refreshSceneCombo() {
        if (cmbScene == null) return;
        refreshingUI = true;
        try {
            cmbScene.setValue(null);
            cmbScene.getItems().setAll(sessionConfig.getScenes());
            cmbScene.setValue(sessionConfig.getActiveScene());
        } finally {
            refreshingUI = false;
        }
    }

    /** Makes {@code target} the active scene: persists, loads its tracks/background/auras, syncs the editor. */
    private void switchScene(Scene target) {
        sessionConfig.setActiveScene(target.getId());
        manager.save(sessionConfig);
        refreshSceneCombo();
        applyScene(sessionConfig.getActiveScene());
        if (sceneEditor != null) sceneEditor.refresh();
    }

    @FXML
    private void onNewScene() {
        TextInputDialog d = new TextInputDialog("New scene");
        d.setTitle("New scene");
        d.setHeaderText(null);
        d.setContentText("Name:");
        d.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name ->
            switchScene(sessionConfig.addScene(name)));
    }

    @FXML
    private void onDuplicateScene() {
        Scene active = sessionConfig.getActiveScene();
        Scene copy = sessionConfig.duplicateScene(active.getId(), active.getName() + " copy");
        if (copy != null) switchScene(copy);
    }

    @FXML
    private void onEditScene() {
        if (sceneEditor == null) {
            sceneEditor = SceneEditorController.create(mainRoot.getScene().getWindow(), this);
        }
        sceneEditor.show();
    }

    /**
     * Applies everything scene-specific: the four mood tracks (a slot without a usable file is
     * unloaded, which stops it if it was playing), background, fit, segment, rate, boomerang
     * and auras. The current mood is kept; a track that was playing keeps playing with the
     * new scene's file (MusicService swaps the player in place).
     */
    private void applyScene(Scene scene) {
        // 1. Tracks
        Map<TrackType, String> assignments = scene.getAssignments();
        for (TrackType t : TrackType.values()) {
            String path = assignments.get(t);
            if (path != null && new File(path).isFile()) {
                if (!path.equals(loadedPaths.get(t))) {
                    loadedPaths.put(t, path);
                    musicService.loadTrack(t, new File(path), () -> onTrackLoaded(t));
                }
            } else {
                loadedPaths.remove(t);
                musicService.unloadTrack(t); // missing file or no assignment
            }
        }

        // 2. Background + fit mode
        applyBackgroundToAmbiance();
        applyFitMode();

        // 3. Auras (legacy bridge until the aura engine exists)
        if (ambianceStage != null) AuraBridge.applyAll(ambianceStage, scene, currentActiveType);

        // 4. UI
        refreshCards();
        updateButtons();
        updatePlayPauseButton();
        resetMoodProgressIfEmpty();
    }

    /** Clears the mood progress bar when the selected mood has no track loaded (stale times otherwise). */
    private void resetMoodProgressIfEmpty() {
        if (musicService.isLoaded(currentActiveType)) return;
        if (progressSlider != null) progressSlider.setValue(0);
        if (lblCurrentTime != null) lblCurrentTime.setText("0:00");
        if (lblTotalTime != null) lblTotalTime.setText("0:00");
    }

    /** Called on the FX thread when a mood track finished loading. */
    private void onTrackLoaded(TrackType type) {
        // Make the selected mood the current one when nothing is playing, so Play uses it
        if (type == currentActiveType && !musicService.isPlaying()
                && musicService.getCurrentTrack() != type) {
            musicService.selectTrack(type);
        }
        refreshCards();
        updateButtons();
        updatePlayPauseButton();
    }

    // --- SceneHost ---

    @Override public SessionConfig session() { return sessionConfig; }

    @Override public void saveSession() { manager.save(sessionConfig); }

    @Override public boolean isFfmpegAvailable() { return ffmpegAvailable; }

    @Override
    public void onSceneRenamed() {
        refreshSceneCombo();
    }

    @Override
    public boolean deleteActiveScene() {
        if (!sessionConfig.deleteScene(sessionConfig.getActiveScene().getId())) return false;
        manager.save(sessionConfig);
        refreshSceneCombo();
        applyScene(sessionConfig.getActiveScene());
        if (sceneEditor != null) sceneEditor.refresh();
        return true;
    }

    @Override
    public void onAuraEdited(AuraSlot slot) {
        if (slot == null) return;
        if (ambianceStage != null && slot != AuraSlot.ON_DEMAND) {
            TrackType mood = TrackType.valueOf(slot.name());
            AuraBridge.applyMood(ambianceStage, sessionConfig.getActiveScene(), mood, mood == currentActiveType);
        }
        refreshCards();
        saveDebounce.playFromStart();
    }

    /**
     * Assigns a track to a mood of the active scene. Pinning (a file copy when the track
     * lives in the YouTube cache) runs off the FX thread; the scene is then updated and the
     * track loaded on the FX thread.
     */
    @Override
    public void assignTrack(TrackType type, File file) {
        if (type == null || file == null || !file.isFile() || !isAudioFile(file)) return;
        final Scene target = sessionConfig.getActiveScene();
        final String source = file.getAbsolutePath();
        CompletableFuture.supplyAsync(() -> TrackLibrary.pin(source), ioExecutor)
            .whenComplete((pinned, err) -> Platform.runLater(() -> {
                String path = (err == null && pinned != null) ? pinned : source;
                // Already pinned: setAssignment's own pin is a no-op here (no IO on the FX thread)
                target.setAssignment(type, path);
                manager.save(sessionConfig);
                if (target == sessionConfig.getActiveScene()) {
                    loadedPaths.put(type, path);
                    musicService.loadTrack(type, new File(path), () -> onTrackLoaded(type));
                    refreshCards();
                    if (sceneEditor != null) sceneEditor.refreshTracks();
                }
            }));
    }

    /**
     * Removes the track of a mood. The player is released through MusicService.unloadTrack,
     * which stops it if that mood was playing.
     */
    @Override
    public void clearTrack(TrackType type) {
        if (type == null) return;
        sessionConfig.getActiveScene().setAssignment(type, null);
        manager.save(sessionConfig);
        loadedPaths.remove(type);
        musicService.unloadTrack(type);
        updateButtons();
        updatePlayPauseButton();
        resetMoodProgressIfEmpty();
        refreshCards();
        if (sceneEditor != null) sceneEditor.refreshTracks();
    }

    @Override
    public void applyBackground() {
        applyBackgroundToAmbiance();
    }

    @Override
    public void applyFitMode() {
        if (ambianceStage == null) return;
        ambianceStage.setBackgroundFitMode(BackgroundFitMode.fromString(sessionConfig.getBackgroundFitMode()));
    }

    /**
     * Pushes the active scene's background to the ambiance: sanitizes the stored path,
     * sets segment and rate BEFORE the background (so the video picks them up on READY),
     * then restores the boomerang from the cache or bakes it.
     */
    private void applyBackgroundToAmbiance() {
        if (ambianceStage == null) return;
        BackgroundConfig bg = sessionConfig.getActiveScene().getBackground();
        String bgPath = bg.getPath();
        // Sanear sesiones corruptas: un archivo de la caché de boomerang jamás es
        // identidad válida de fondo (estado derivado; puede ser purgado en cualquier momento)
        if (bgPath != null && boomerangBaker.isCachePath(bgPath)) {
            bgPath = null;
            bg.setPath(null);
            bg.setSegmentStartMs(null);
            bg.setSegmentEndMs(null);
            bg.setBoomerangEnabled(false);
        } else if (bgPath != null && !new File(bgPath).exists()) {
            // La ruta fue guardada pero el archivo ya no existe: limpiar para evitar
            // que session.json vuelva a persistir un path roto en la próxima sesión.
            bgPath = null;
            bg.setPath(null);
        }

        Long segStart = bg.getSegmentStartMs();
        Long segEnd   = bg.getSegmentEndMs();
        double rate = sessionConfig.getVideoPlaybackRate();

        // Drop any boomerang/segment left by the previous background before the new one loads:
        // the video branch of setBackground trusts the state set by the caller.
        ambianceStage.resetBoomerang();

        if (bgPath == null) {
            ambianceStage.clearVideoSegment();
            ambianceStage.setBackground(null);
            return;
        }

        boolean isVideo = "Video".equals(BackgroundEditorController.mediaType(bgPath));
        if (isVideo && segStart != null && segEnd != null) {
            // Establecer el segmento ANTES de setBackground para que setVideo() lo reciba en READY
            ambianceStage.setVideoSegment(Duration.millis(segStart), Duration.millis(segEnd));
        } else {
            // Also guards against a stale segment left from a previous background type
            if (!isVideo) {
                bg.setSegmentStartMs(null);
                bg.setSegmentEndMs(null);
            }
            ambianceStage.clearVideoSegment();
        }
        // Establecer la velocidad ANTES de setBackground para que setVideo() la lea al crear el player
        ambianceStage.setVideoRate(rate);
        ambianceStage.setBackground(bgPath);

        // Boomerang restore: applied state to the live ambiance
        boolean wantBoomerang = Boolean.TRUE.equals(bg.getBoomerangEnabled());
        if (wantBoomerang && isVideo && segStart != null && segEnd != null && ffmpegAvailable) {
            if (boomerangBaker.isCached(bgPath, segStart, segEnd)) {
                ambianceStage.activateBoomerang(
                        boomerangBaker.cacheKeyPath(bgPath, segStart, segEnd).toString());
            } else {
                startBake(bgPath, segStart, segEnd);
            }
        } else {
            bg.setBoomerangEnabled(false);
        }
    }

    // --- Live ambiance boomerang state (used when a scene background is applied) ---

    /**
     * Injects the ffmpeg availability result before applySession.
     */
    public void setFfmpegAvailable(boolean available) {
        this.ffmpegAvailable = available;
    }

    /**
     * Starts an async bake for the live ambiance.
     */
    private void startBake(String src, long s, long e) {
        bakeGuardSrc   = src;
        bakeGuardStart = s;
        bakeGuardEnd   = e;
        boomerangBaker.bakeAsync(src, s, e,
                outPath -> Platform.runLater(() -> onBakeSuccess(outPath, src, s, e)),
                err     -> Platform.runLater(() -> onBakeFailure(err, src, s, e)));
    }

    /** True while the active scene still wants exactly this background and segment. */
    private boolean isBakeStillWanted(String src, long s, long e) {
        return
                src.equals(sessionConfig.getBackgroundImagePath())
                && sessionConfig.getVideoSegmentStartMs() != null
                && s == sessionConfig.getVideoSegmentStartMs()
                && sessionConfig.getVideoSegmentEndMs() != null
                && e == sessionConfig.getVideoSegmentEndMs()
                && src.equals(bakeGuardSrc)
                && s == bakeGuardStart
                && e == bakeGuardEnd;
    }

    private void onBakeSuccess(java.nio.file.Path out, String src, long s, long e) {
        if (!isBakeStillWanted(src, s, e)) return;
        ambianceStage.activateBoomerang(out.toString());
    }

    private void onBakeFailure(String stderrTail, String src, long s, long e) {
        System.err.println(stderrTail);
        // A failed bake for a scene the user already left must not clear the new scene's flag
        if (!isBakeStillWanted(src, s, e)) return;
        if (ambianceStage != null) ambianceStage.deactivateBoomerang();
        sessionConfig.setBoomerangEnabled(false);
        manager.save(sessionConfig);
    }

    // --- Ambiance y selección de pantalla ---

    @FXML
    private void onToggleAmbiance() {
        if (ambianceStage == null) return;
        if (ambianceStage.isVisible()) ambianceStage.hide();
        else ambianceStage.show();
    }

    private void updateAmbianceToggle(boolean showing) {
        if (btnToggleAmbiance == null) return;
        btnToggleAmbiance.setText(showing ? "Hide ambiance" : "Show ambiance");
    }

    /**
     * Repopula el menú de pantallas sin disparar la selección.
     * Conserva la selección previa si el índice sigue siendo válido.
     */
    private void populateScreenMenu() {
        if (btnToggleAmbiance == null) return;
        int previousIndex = selectedScreenItem();
        List<Screen> screens = Screen.getScreens();

        btnToggleAmbiance.getItems().clear();
        screenGroup.getToggles().clear();
        addScreenItem("Auto (recommended)", 0);
        for (int i = 0; i < screens.size(); i++) {
            Screen s = screens.get(i);
            int w = (int) s.getBounds().getWidth();
            int h = (int) s.getBounds().getHeight();
            String tag = s.equals(Screen.getPrimary()) ? " (primary)" : "";
            addScreenItem("Screen " + (i + 1) + " — " + w + "x" + h + tag, i + 1);
        }

        // Restaurar selección previa si sigue siendo válida; si no, seleccionar "Auto"
        selectScreenItem(previousIndex >= 0 && previousIndex < screenItemCount() ? previousIndex : 0);
    }

    private void addScreenItem(String text, int menuIndex) {
        RadioMenuItem item = new RadioMenuItem(text);
        item.setToggleGroup(screenGroup);
        item.setOnAction(e -> onScreenSelected(menuIndex));
        btnToggleAmbiance.getItems().add(item);
    }

    private int screenItemCount() {
        return btnToggleAmbiance == null ? 0 : btnToggleAmbiance.getItems().size();
    }

    private int selectedScreenItem() {
        if (btnToggleAmbiance == null) return -1;
        Toggle selected = screenGroup.getSelectedToggle();
        return selected == null ? -1 : btnToggleAmbiance.getItems().indexOf(selected);
    }

    private void selectScreenItem(int index) {
        if (btnToggleAmbiance == null || index < 0 || index >= screenItemCount()) return;
        ((RadioMenuItem) btnToggleAmbiance.getItems().get(index)).setSelected(true);
    }

    /** Menu index 0 → Auto (-1); index N → screen N-1. */
    private void onScreenSelected(int menuIndex) {
        if (refreshingUI) return;
        int resolvedIndex = menuIndex <= 0 ? -1 : menuIndex - 1;
        sessionConfig.setAmbianceScreenIndex(resolvedIndex);
        manager.save(sessionConfig);
        if (ambianceStage != null) {
            ambianceStage.applyScreen(resolvedIndex);
        }
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

    // --- Biblioteca: celdas del árbol (drag + menú contextual) ---

    /** Tree cell for the library: drag a track onto a mood card, or use the "Assign to" context menu. */
    private final class LibraryCell extends TreeCell<File> {
        private final ContextMenu menu = new ContextMenu();

        LibraryCell() {
            Menu assign = new Menu("Assign to");
            assign.getItems().addAll(
                    assignItem("Paused", TrackType.PAUSED),
                    assignItem("Normal", TrackType.NORMAL),
                    assignItem("Raged", TrackType.RAGED),
                    assignItem("Advantage", TrackType.ADVANTAGE));
            menu.getItems().add(assign);

            setOnDragDetected(e -> {
                File f = getItem();
                if (f == null || f.isDirectory()) return;
                Dragboard db = startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putFiles(List.of(f));
                db.setContent(content);
                e.consume();
            });
        }

        private MenuItem assignItem(String label, TrackType type) {
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> {
                File f = getItem();
                if (f != null && f.isFile()) assignTrack(type, f);
            });
            return item;
        }

        @Override
        protected void updateItem(File item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
                return;
            }
            setText(item.isDirectory() ? "📁 " + item.getName() : item.getName());
            setContextMenu(item.isDirectory() ? null : menu);
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

    @FXML
    private void onAddYoutubeUrl() {
        if (lblYoutubeError != null) { lblYoutubeError.setText(""); lblYoutubeError.setVisible(false); lblYoutubeError.setManaged(false); }

        String pname = cmbPlaylist.getValue();
        if (pname == null || pname.isBlank()) {
            showYoutubeError("Select a playlist first.");
            return;
        }

        String url = txtYoutubeUrl != null ? txtYoutubeUrl.getText().trim() : "";
        Optional<String> vid = YouTubeDownloader.extractVideoId(url);
        if (vid.isEmpty()) {
            showYoutubeError("Not a valid YouTube URL.");
            return;
        }

        if (!YouTubeDownloader.isYtDlpAvailable()) {
            new Alert(Alert.AlertType.ERROR,
                    "yt-dlp not found on PATH. Install it to use this feature.").showAndWait();
            return;
        }

        final String capturedUrl = url;
        final String capturedVid = vid.get();
        final String placeholderText = "⬇ Downloading... (" + capturedVid + ")";

        if (btnAddYoutube != null) btnAddYoutube.setDisable(true);
        lstPlaylistTracks.getItems().add(placeholderText);
        if (txtYoutubeUrl != null) txtYoutubeUrl.clear();

        youTubeDownloader.downloadAsync(capturedUrl, capturedVid,
                result -> Platform.runLater(() -> {
                    PlaylistItem item = new PlaylistItem(
                            result.getPath(), capturedUrl, result.getTitle());
                    playlistManager.addItem(pname, item);
                    persistPlaylists();
                    if (btnAddYoutube != null) btnAddYoutube.setDisable(false);
                    refreshPlaylistUI();
                }),
                err -> Platform.runLater(() -> {
                    if (btnAddYoutube != null) btnAddYoutube.setDisable(false);
                    refreshPlaylistUI();
                    new Alert(Alert.AlertType.ERROR, "Download failed: " + err).showAndWait();
                })
        );
    }

    @FXML
    private void onBrowseYouTube() {
        if (!YouTubeDownloader.isYtDlpAvailable()) {
            new Alert(Alert.AlertType.ERROR,
                    "yt-dlp not found on PATH. Install it to use this feature.").showAndWait();
            return;
        }
        Stage owner = (Stage) btnBrowseYoutube.getScene().getWindow();
        YoutubePicker.show(owner, youTubeDownloader, result -> {
            txtYoutubeUrl.setText(result.url());
            onAddYoutubeUrl();
        });
    }

    private void showYoutubeError(String message) {
        if (lblYoutubeError != null) {
            lblYoutubeError.setText(message);
            lblYoutubeError.setVisible(true);
            lblYoutubeError.setManaged(true);
        } else {
            new Alert(Alert.AlertType.ERROR, message).showAndWait();
        }
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

        // Repopulate track ListView with display names (title for YouTube tracks, filename otherwise)
        if (lstPlaylistTracks != null) {
            lstPlaylistTracks.getItems().setAll(
                playlistManager.getActiveItems().stream()
                    .map(PlaylistItem::displayName)
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
        // Keep the loaded scenes (ids, assignments, backgrounds, auras); scene-specific state is
        // restored by applyScene below. Only session-wide settings are restored here.
        sessionConfig.adoptScenesFrom(cfg);

        String lastImgDir = cfg.getLastImageFolderPath();
        if (lastImgDir != null) {
            sessionConfig.setLastImageFolderPath(lastImgDir);
        }

        // Restaurar índice de pantalla de ambiance
        int screenIdx = cfg.getAmbianceScreenIndex();
        sessionConfig.setAmbianceScreenIndex(screenIdx);
        Platform.runLater(() -> {
            populateScreenMenu();
            // Seleccionar el ítem correcto en el menú (índice -1 → posición 0 "Auto")
            int menuPos = screenIdx < 0 ? 0 : screenIdx + 1;
            // Si el índice recordado está fuera del rango disponible, mostrar "Auto"
            // sin sobrescribir el índice guardado — la intención se conserva.
            selectScreenItem(menuPos < screenItemCount() ? menuPos : 0);
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
        // Carry the global playlist state over, or the next save would drop it from session.json
        sessionConfig.setPlaylists(cfg.getPlaylists());
        sessionConfig.setActiveMode(cfg.getActiveMode());
        sessionConfig.setActivePlaylistName(cfg.getActivePlaylistName());
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

        // Aplicar en UI (puede ejecutarse antes de show(); Platform.runLater es seguro)
        Platform.runLater(() -> {
            // Restaurar volumen maestro desde sesión
            double mv = cfg.getMasterVolume();
            sessionConfig.setMasterVolume(mv);
            musicService.setMasterVolume(mv);
            if (sldMasterVolume != null) sldMasterVolume.setValue(mv); // listener fires -> idempotent
            if (lblMasterVolume != null)
                lblMasterVolume.setText(Math.round(mv * 100) + "%");

            // Scene part: tracks, background, auras. Runs after the ambiance stage has opened.
            refreshSceneCombo();
            applyScene(sessionConfig.getActiveScene());
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
        if (sceneEditor != null) sceneEditor.dispose(); // releases the background preview player
        if (saveDebounce.getStatus() == Animation.Status.RUNNING) {
            saveDebounce.stop();
            manager.save(sessionConfig); // flush a pending editor save
        }
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

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String capitalize(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
