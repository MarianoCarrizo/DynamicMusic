package com.marianocarrizo.dynamicMusic.Controller;

import com.marianocarrizo.dynamicMusic.ambiance.BackgroundFitMode;
import com.marianocarrizo.dynamicMusic.ambiance.BoomerangBaker;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background section of the scene editor: choose a file, fit mode and, for videos, the
 * preview with A-B segment, speed and boomerang. Edits are staged in the pending* fields and
 * previewed locally; nothing touches the scene or the ambiance until Apply, which writes the
 * active scene's background and asks the host to re-apply it live.
 */
public class BackgroundEditorController {

    @FXML private Label lblBackgroundFile;
    @FXML private Label lblBackgroundType;
    @FXML private Button btnEditBackground;
    @FXML private ComboBox<BackgroundFitMode> cmbFitMode;

    @FXML private VBox            bgPreviewPanel;
    @FXML private StackPane       bgPreviewStack;
    @FXML private MediaView       bgPreviewMediaView;
    @FXML private ImageView       bgPreviewImageView;
    @FXML private ProgressIndicator bgPreviewSpinner;
    @FXML private Label           lblBgPreviewPlaceholder;
    @FXML private Slider          bgPreviewSlider;
    @FXML private Pane            bgMarkBand;
    @FXML private Label           lblBgPreviewCurrentTime;
    @FXML private Label           lblBgPreviewTotalTime;
    @FXML private Button          btnBgMarkStart;
    @FXML private Button          btnBgMarkEnd;
    @FXML private Label           lblBgSegmentInfo;
    @FXML private Button          btnBgClearSegment;
    @FXML private ComboBox<Double> cmbBgVideoSpeed;
    @FXML private ToggleButton    btnBgBoomerang;
    @FXML private Label           lblBgBoomerangHint;
    @FXML private Button          btnApplyBackground;
    @FXML private Button          btnCancelBackground;

    private SceneHost host;
    private boolean refreshingUI = false;

    private final BoomerangBaker boomerangBaker = new BoomerangBaker();

    // --- preview state (ephemeral; never written to the scene until Apply) ---
    private final ExecutorService bgPrep = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bg-preview-init");
        t.setDaemon(true);
        return t;
    });
    private MediaPlayer bgPreviewPlayer       = null;
    private String      pendingBgPath         = null;  // null = idle
    private Duration    pendingBgStart        = null;
    private Duration    pendingBgEnd          = null;
    private double      pendingBgRate         = 1.0;
    private boolean     pendingBoomerangOn    = false;
    private String      pendingBakedPath      = null;
    private int         bgPreviewGeneration   = 0;
    private boolean     isDraggingBgPreviewSlider = false;
    // Stale-bake guard snapshots (captured at bake start, compared in onBgBakeSuccess)
    private String bgBakeGuardSrc;
    private long   bgBakeGuardStart;
    private long   bgBakeGuardEnd;

    // Marked range A-B + ticks; children of bgMarkBand
    private final Rectangle bgMarkRange = new Rectangle();
    private final Rectangle bgMarkTickA = new Rectangle();
    private final Rectangle bgMarkTickB = new Rectangle();

    @FXML
    public void initialize() {
        cmbFitMode.setConverter(new StringConverter<>() {
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

        cmbBgVideoSpeed.getItems().setAll(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0);
        cmbBgVideoSpeed.setConverter(new StringConverter<>() {
            @Override public String toString(Double v)   { return v == null ? "" : v + "x"; }
            @Override public Double fromString(String s) { return null; }
        });
        cmbBgVideoSpeed.setValue(1.0);

        // Clip so the MediaView does not overflow the StackPane when the video is larger than the panel
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(bgPreviewStack.widthProperty());
        clip.heightProperty().bind(bgPreviewStack.heightProperty());
        bgPreviewStack.setClip(clip);

        bgMarkBand.widthProperty().addListener((obs, o, n) -> updateBgMarkOverlay());
    }

    /** Wires the host; call once right after the FXML is loaded. */
    void init(SceneHost host) {
        this.host = host;
        refresh();
    }

    // --- Lifecycle driven by the scene editor ---

    /** Reloads everything from the active scene. Discards any pending (unapplied) edit. */
    void refresh() {
        onCancelBackground();
        SessionConfig session = host.session();
        updateBackgroundLabel(session.getBackgroundImagePath());
        refreshingUI = true;
        try {
            cmbFitMode.setValue(BackgroundFitMode.fromString(session.getBackgroundFitMode()));
        } finally {
            refreshingUI = false;
        }
    }

    /** Refreshes only the file labels (background changed from outside, e.g. dropped on the ambiance). */
    void refreshLabel() {
        updateBackgroundLabel(host.session().getBackgroundImagePath());
    }

    /** Releases the preview player; call when the editor window closes or the app exits. */
    void dispose() {
        onCancelBackground();
    }

    // --- File selection ---

    @FXML
    private void onSetBackground() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select background for ambiance");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Background (images/video)",
                        "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.mp4", "*.avi"));

        String lastImgDir = host.session().getLastImageFolderPath();
        if (lastImgDir != null) {
            File dir = new File(lastImgDir);
            if (dir.isDirectory()) chooser.setInitialDirectory(dir);
        }

        File file = chooser.showOpenDialog(bgPreviewPanel.getScene().getWindow());
        if (file == null) return;

        // Dispose any existing preview before loading the new file
        disposeBgPreview();
        // Reset all pending fields; the caller owns pendingBgPath (SOURCE path)
        pendingBgPath = file.getAbsolutePath();
        pendingBgStart = null;
        pendingBgEnd   = null;
        pendingBakedPath = null;
        pendingBoomerangOn = false;
        pendingBgRate = 1.0;
        resetPendingControls();
        // Load file into preview; the ambiance is NOT touched here
        loadBgPreview(pendingBgPath);
    }

    /** Puts speed and boomerang controls back to the pending defaults without firing handlers. */
    private void resetPendingControls() {
        refreshingUI = true;
        try {
            cmbBgVideoSpeed.setValue(pendingBgRate);
            btnBgBoomerang.setSelected(pendingBoomerangOn);
        } finally {
            refreshingUI = false;
        }
        if (lblBgBoomerangHint != null) lblBgBoomerangHint.setText("");
        if (lblBgSegmentInfo != null) lblBgSegmentInfo.setText("Full video");
    }

    /**
     * Disposes the bg preview player and resets the ImageView.
     * Called when cancelling, replacing the file, applying and closing.
     */
    private void disposeBgPreview() {
        bgPreviewGeneration++;
        if (bgPreviewPlayer != null) {
            bgPreviewPlayer.stop();
            bgPreviewPlayer.dispose();
            bgPreviewPlayer = null;
        }
        bgPreviewImageView.setImage(null);
    }

    /**
     * Loads a file (video or image/GIF) into the bg preview panel.
     * Caller must call disposeBgPreview() first if a previous preview was active.
     */
    private void loadBgPreview(String absolutePath) {
        // Do NOT assign pendingBgPath here: the caller owns the SOURCE path.
        // This method also plays baked (boomerang) files, which must never
        // become the identity of the pending background.

        bgPreviewPanel.setVisible(true);
        bgPreviewPanel.setManaged(true);

        bgPreviewMediaView.setVisible(false);
        bgPreviewImageView.setVisible(false);
        bgPreviewSpinner.setVisible(true);
        lblBgPreviewPlaceholder.setVisible(false);

        String ext = absolutePath.toLowerCase();
        int dot = ext.lastIndexOf('.');
        String extPart = dot >= 0 ? ext.substring(dot + 1) : "";

        boolean isVideo = extPart.equals("mp4") || extPart.equals("avi");

        if (!isVideo) {
            // Image / GIF branch
            Image img = new Image(new File(absolutePath).toURI().toString(), true);
            bgPreviewImageView.setImage(img);
            bgPreviewImageView.setVisible(true);
            bgPreviewSpinner.setVisible(false);
            disableVideoControls();
        } else {
            final int gen = ++bgPreviewGeneration;
            final String uri = new File(absolutePath).toURI().toString();
            disableVideoControls();

            bgPrep.submit(() -> {
                Media media;
                try {
                    media = new Media(uri);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        bgPreviewSpinner.setVisible(false);
                        lblBgPreviewPlaceholder.setText("Error loading preview");
                        lblBgPreviewPlaceholder.setVisible(true);
                    });
                    return;
                }
                final Media readyMedia = media;
                Platform.runLater(() -> {
                    if (gen != bgPreviewGeneration) {
                        new MediaPlayer(readyMedia).dispose();
                        return;
                    }
                    MediaPlayer player = new MediaPlayer(readyMedia);
                    bgPreviewPlayer = player;
                    player.setMute(true);
                    player.setCycleCount(MediaPlayer.INDEFINITE);
                    // The preview does NOT apply startTime/stopTime: it is used to re-mark, so
                    // scrubbing must cover the whole video; the loop is shown in the label.
                    player.setRate(pendingBgRate);
                    bgPreviewMediaView.setMediaPlayer(player);
                    bgPreviewMediaView.setVisible(true);

                    // The Media is already parsed on the background thread: READY can happen
                    // before the handler is registered and setOnReady does not fire retroactively.
                    final boolean[] bgReadyFired = { false };
                    Runnable bgReadyHandler = () -> {
                        if (bgReadyFired[0]) return;
                        bgReadyFired[0] = true;
                        if (bgPreviewPlayer == player && gen == bgPreviewGeneration) {
                            bgPreviewSpinner.setVisible(false);
                            bgPreviewSlider.setDisable(false);
                            btnBgMarkStart.setDisable(false);
                            btnBgClearSegment.setDisable(false);
                            cmbBgVideoSpeed.setDisable(false);
                            updateBgBoomerangEnablement();
                            // Duration is known now: refresh label and marks band
                            updateBgSegmentLabel();
                            Duration total = player.getTotalDuration();
                            if (total != null && !total.isUnknown() && !total.isIndefinite()) {
                                lblBgPreviewTotalTime.setText(formatSegmentTime(total));
                            }
                            player.currentTimeProperty().addListener((obs, o, cur) -> {
                                if (!isDraggingBgPreviewSlider && bgPreviewPlayer != null) {
                                    Duration tot = bgPreviewPlayer.getTotalDuration();
                                    if (tot != null && !tot.isUnknown() && !tot.isIndefinite()) {
                                        bgPreviewSlider.setValue(cur.toMillis() / tot.toMillis() * 100.0);
                                        lblBgPreviewCurrentTime.setText(formatSegmentTime(cur));
                                        lblBgPreviewTotalTime.setText(formatSegmentTime(tot));
                                    }
                                }
                            });
                            bgPreviewSlider.setOnMousePressed(e -> isDraggingBgPreviewSlider = true);
                            bgPreviewSlider.setOnMouseReleased(e -> {
                                isDraggingBgPreviewSlider = false;
                                if (bgPreviewPlayer != null) {
                                    Duration tot = bgPreviewPlayer.getTotalDuration();
                                    if (tot != null)
                                        bgPreviewPlayer.seek(tot.multiply(bgPreviewSlider.getValue() / 100.0));
                                }
                            });
                        }
                    };
                    player.setOnReady(bgReadyHandler);
                    player.play();
                    if (player.getStatus() == MediaPlayer.Status.READY) bgReadyHandler.run();
                });
            });
        }
    }

    private void disableVideoControls() {
        bgPreviewSlider.setDisable(true);
        btnBgMarkStart.setDisable(true);
        btnBgMarkEnd.setDisable(true);
        btnBgClearSegment.setDisable(true);
        cmbBgVideoSpeed.setDisable(true);
        btnBgBoomerang.setDisable(true);
    }

    /** Updates lblBgSegmentInfo with the current pending marks (with guidance text). */
    private void updateBgSegmentLabel() {
        if (pendingBgStart == null) {
            lblBgSegmentInfo.setText("Full video - press Mark start during playback");
        } else if (pendingBgEnd == null) {
            lblBgSegmentInfo.setText("Start " + formatSegmentTime(pendingBgStart)
                    + " - now press Mark end");
        } else {
            lblBgSegmentInfo.setText("Loop: "
                    + formatSegmentTime(pendingBgStart)
                    + " - "
                    + formatSegmentTime(pendingBgEnd));
        }
        updateBgMarkOverlay();
    }

    /** Draws the marked A-B range over the band aligned with the preview scrub bar. */
    private void updateBgMarkOverlay() {
        if (bgMarkBand == null) return;
        if (bgMarkBand.getChildren().isEmpty()) {
            bgMarkRange.setFill(Color.web("#1db954", 0.45));
            bgMarkTickA.setFill(Color.web("#1db954"));
            bgMarkTickB.setFill(Color.web("#1db954"));
            bgMarkBand.getChildren().addAll(bgMarkRange, bgMarkTickA, bgMarkTickB);
        }
        double w = bgMarkBand.getWidth();
        Duration tot = bgPreviewPlayer != null ? bgPreviewPlayer.getTotalDuration() : null;
        boolean valid = w > 0 && tot != null && !tot.isUnknown() && !tot.isIndefinite()
                && tot.toMillis() > 0;
        double aFrac = (valid && pendingBgStart != null)
                ? Math.min(pendingBgStart.toMillis() / tot.toMillis(), 1.0) : -1;
        double bFrac = (valid && pendingBgEnd != null)
                ? Math.min(pendingBgEnd.toMillis() / tot.toMillis(), 1.0) : -1;
        bgMarkTickA.setVisible(aFrac >= 0);
        if (aFrac >= 0) {
            bgMarkTickA.setX(aFrac * w - 1); bgMarkTickA.setY(0);
            bgMarkTickA.setWidth(2);         bgMarkTickA.setHeight(6);
        }
        bgMarkTickB.setVisible(bFrac >= 0);
        if (bFrac >= 0) {
            bgMarkTickB.setX(bFrac * w - 1); bgMarkTickB.setY(0);
            bgMarkTickB.setWidth(2);         bgMarkTickB.setHeight(6);
        }
        boolean hasRange = aFrac >= 0 && bFrac > aFrac;
        bgMarkRange.setVisible(hasRange);
        if (hasRange) {
            bgMarkRange.setX(aFrac * w);               bgMarkRange.setY(1);
            bgMarkRange.setWidth((bFrac - aFrac) * w); bgMarkRange.setHeight(4);
        }
    }

    @FXML
    private void onSelectFitMode() {
        if (refreshingUI) return;
        BackgroundFitMode mode = cmbFitMode.getValue();
        if (mode == null) return;
        host.session().setBackgroundFitMode(mode.name());
        host.saveSession();
        host.applyFitMode();
    }

    // --- Preview panel handlers ---

    @FXML
    private void onCancelBackground() {
        disposeBgPreview();
        pendingBgPath = null;
        pendingBgStart = pendingBgEnd = null;
        pendingBakedPath = null;
        pendingBoomerangOn = false;
        pendingBgRate = 1.0;
        resetPendingControls();
        bgPreviewPanel.setVisible(false);
        bgPreviewPanel.setManaged(false);
    }

    @FXML
    private void onBgMarkStart() {
        if (bgPreviewPlayer == null) return;
        Duration pos = bgPreviewPlayer.getCurrentTime();
        if (pendingBgEnd != null && pendingBgEnd.subtract(pos).lessThan(Duration.millis(500))) {
            lblBgSegmentInfo.setText("Start must be at least 0.5s before end");
            return;
        }
        pendingBgStart = pos;
        updateBgSegmentLabel();
        btnBgMarkEnd.setDisable(false);
        updateBgBoomerangEnablement();
    }

    @FXML
    private void onBgMarkEnd() {
        if (bgPreviewPlayer == null || pendingBgStart == null) {
            lblBgSegmentInfo.setText("Mark a start position first");
            return;
        }
        Duration pos = bgPreviewPlayer.getCurrentTime();
        Duration gap = pos.subtract(pendingBgStart);
        if (gap.lessThan(Duration.millis(500))) {
            lblBgSegmentInfo.setText("End must be at least 0.5s after start");
            return;
        }
        pendingBgEnd = pos;
        // Do NOT apply startTime/stopTime to the preview: it must keep covering the whole
        // video so the marks can be redone; the range is drawn on the band over the scrub bar.
        updateBgSegmentLabel();
        updateBgBoomerangEnablement();
    }

    @FXML
    private void onBgClearSegment() {
        // If boomerang is ON, revert it first
        if (pendingBoomerangOn) revertBgBoomerangToggle();
        pendingBgStart = pendingBgEnd = null;
        pendingBgRate = 1.0;
        refreshingUI = true;
        try { cmbBgVideoSpeed.setValue(1.0); } finally { refreshingUI = false; }
        if (bgPreviewPlayer != null) {
            bgPreviewPlayer.setStartTime(Duration.ZERO);
            Duration total = bgPreviewPlayer.getTotalDuration();
            if (total != null && !total.isUnknown() && !total.isIndefinite())
                bgPreviewPlayer.setStopTime(total);
        }
        lblBgSegmentInfo.setText("Full video");
        btnBgMarkEnd.setDisable(true);
        updateBgBoomerangEnablement();
    }

    @FXML
    private void onSelectBgVideoSpeed() {
        if (refreshingUI) return;
        Double v = cmbBgVideoSpeed.getValue();
        if (v == null) return;
        pendingBgRate = v;
        if (bgPreviewPlayer != null) bgPreviewPlayer.setRate(v);
    }

    private void updateBgBoomerangEnablement() {
        boolean hasSegment = pendingBgStart != null && pendingBgEnd != null;
        boolean videoReady = bgPreviewPlayer != null;
        btnBgBoomerang.setDisable(!(host.isFfmpegAvailable() && hasSegment && videoReady));
    }

    private void revertBgBoomerangToggle() {
        refreshingUI = true;
        try { btnBgBoomerang.setSelected(false); } finally { refreshingUI = false; }
        pendingBoomerangOn = false;
        pendingBakedPath = null;
    }

    private void disposePreviewPlayerOnly() {
        bgPreviewGeneration++;
        if (bgPreviewPlayer != null) {
            bgPreviewPlayer.stop();
            bgPreviewPlayer.dispose();
            bgPreviewPlayer = null;
        }
        bgPreviewImageView.setImage(null);
    }

    @FXML
    private void onToggleBgBoomerang() {
        if (refreshingUI) return;
        if (pendingBgPath == null) return;

        boolean wantOn = btnBgBoomerang.isSelected();
        if (!wantOn) {
            // Toggle OFF: reload original preview with marks
            revertBgBoomerangToggle();
            // Dispose current baked preview, reload from original
            disposePreviewPlayerOnly();
            btnBgMarkStart.setDisable(false);
            btnBgMarkEnd.setDisable(pendingBgStart == null);
            btnBgClearSegment.setDisable(false);
            loadBgPreview(pendingBgPath);
            return;
        }

        // Toggle ON
        if (pendingBgStart == null || pendingBgEnd == null) {
            revertBgBoomerangToggle();
            return;
        }
        pendingBoomerangOn = true;

        long sMs = (long) pendingBgStart.toMillis();
        long eMs = (long) pendingBgEnd.toMillis();

        if (boomerangBaker.isCached(pendingBgPath, sMs, eMs)) {
            // Cache hit: load baked file directly
            pendingBakedPath = boomerangBaker.cacheKeyPath(pendingBgPath, sMs, eMs).toString();
            disposePreviewPlayerOnly();
            // Disable mark buttons while boomerang ON (palindrome marks are meaningless)
            btnBgMarkStart.setDisable(true);
            btnBgMarkEnd.setDisable(true);
            btnBgClearSegment.setDisable(true);
            loadBgPreview(pendingBakedPath);
            return;
        }

        // Cache miss: bake asynchronously
        bgBakeGuardSrc   = pendingBgPath;
        bgBakeGuardStart = sMs;
        bgBakeGuardEnd   = eMs;
        final String src = pendingBgPath;
        bgPreviewSpinner.setVisible(true);
        btnBgBoomerang.setDisable(true);
        // Apply would read the half-written cache file or start a second ffmpeg on it
        btnApplyBackground.setDisable(true);
        lblBgBoomerangHint.setText("Baking boomerang...");
        boomerangBaker.bakeAsync(src, sMs, eMs,
                outPath -> Platform.runLater(() -> onBgBakeSuccess(outPath, src, sMs, eMs)),
                err     -> Platform.runLater(() -> onBgBakeFailure(err)));
    }

    private void onBgBakeSuccess(Path out, String srcPath, long sMs, long eMs) {
        btnApplyBackground.setDisable(false);
        // Stale-bake guard: check the pending* snapshot (the preview is ephemeral)
        boolean stillValid = pendingBoomerangOn
                && pendingBgPath != null && pendingBgPath.equals(srcPath)
                && pendingBgStart != null && (long) pendingBgStart.toMillis() == sMs
                && pendingBgEnd   != null && (long) pendingBgEnd.toMillis()   == eMs
                && srcPath.equals(bgBakeGuardSrc)
                && sMs == bgBakeGuardStart
                && eMs == bgBakeGuardEnd;

        if (!stillValid) {
            bgPreviewSpinner.setVisible(false);
            updateBgBoomerangEnablement();
            return;
        }
        pendingBakedPath = out.toString();
        bgPreviewSpinner.setVisible(false);
        lblBgBoomerangHint.setText("");
        // Dispose current preview and load baked palindrome
        disposePreviewPlayerOnly();
        // Disable mark buttons (boomerang ON state)
        btnBgMarkStart.setDisable(true);
        btnBgMarkEnd.setDisable(true);
        btnBgClearSegment.setDisable(true);
        updateBgBoomerangEnablement();
        loadBgPreview(pendingBakedPath);
    }

    private void onBgBakeFailure(String stderrTail) {
        btnApplyBackground.setDisable(false);
        bgPreviewSpinner.setVisible(false);
        revertBgBoomerangToggle();
        updateBgBoomerangEnablement();
        lblBgBoomerangHint.setText("Bake failed");
        PauseTransition pt = new PauseTransition(Duration.seconds(3));
        pt.setOnFinished(ev -> lblBgBoomerangHint.setText(""));
        pt.play();
        System.err.println(stderrTail);
    }

    @FXML
    private void onApplyBackground() {
        if (pendingBgPath == null) return;
        // 1. Persist into the active scene (ONE save)
        SessionConfig session = host.session();
        session.setBackgroundImagePath(pendingBgPath);
        session.setLastImageFolderPath(new File(pendingBgPath).getParent());
        session.setVideoSegmentStartMs(pendingBgStart == null ? null : (long) pendingBgStart.toMillis());
        session.setVideoSegmentEndMs(pendingBgEnd == null ? null : (long) pendingBgEnd.toMillis());
        session.setVideoPlaybackRate(pendingBgRate);
        session.setBoomerangEnabled(pendingBoomerangOn);
        host.saveSession();
        // 2. Live ambiance (segment, rate, boomerang cache hit/bake) is applied by the host
        host.applyBackground();
        // 3. Update labels, dispose the preview and clear the pending state
        updateBackgroundLabel(session.getBackgroundImagePath());
        onCancelBackground();
    }

    @FXML
    private void onEditBackground() {
        SessionConfig session = host.session();
        String applied = session.getBackgroundImagePath();
        if (applied == null || !new File(applied).exists()) return;
        // Edit replaces any active preview: release the previous player first
        disposeBgPreview();
        // Preload pending* from the applied state
        pendingBgPath = applied;
        Long sMs = session.getVideoSegmentStartMs();
        Long eMs = session.getVideoSegmentEndMs();
        pendingBgStart = sMs == null ? null : Duration.millis(sMs);
        pendingBgEnd   = eMs == null ? null : Duration.millis(eMs);
        double savedRate = session.getVideoPlaybackRate();
        pendingBgRate  = savedRate != 0 ? savedRate : 1.0;
        boolean boomOn = Boolean.TRUE.equals(session.getBoomerangEnabled());
        pendingBoomerangOn = boomOn;
        // Cache hit check for the boomerang baked path
        if (boomOn && sMs != null && eMs != null
                && boomerangBaker.isCached(applied, sMs, eMs)) {
            pendingBakedPath = boomerangBaker.cacheKeyPath(applied, sMs, eMs).toString();
        } else {
            pendingBakedPath = null;
            pendingBoomerangOn = false; // cannot restore boomerang if not cached; user re-toggles
        }
        // Load preview (no setBackground on the ambiance)
        String previewSource = (pendingBakedPath != null && pendingBoomerangOn)
                ? pendingBakedPath : applied;
        loadBgPreview(previewSource);
        // Restore controls under the refreshingUI guard
        resetPendingControls();
        // Re-enable Mark End if a start mark was restored: loadBgPreview disables it initially
        // and setOnReady does not re-enable it
        if (pendingBgStart != null) {
            btnBgMarkEnd.setDisable(false);
        }
        updateBgSegmentLabel();
    }

    // --- File labels ---

    /** Updates the background file labels and the Edit button state. */
    private void updateBackgroundLabel(String path) {
        if (path == null || !new File(path).exists()) {
            lblBackgroundFile.setText("No background set");
            lblBackgroundType.setText("");
            btnEditBackground.setDisable(true);
        } else {
            lblBackgroundFile.setText(new File(path).getName());
            lblBackgroundType.setText(mediaType(path));
            btnEditBackground.setDisable(false);
        }
    }

    /** Infers the media type from the extension: "Image", "GIF", "Video" or "" if unknown. */
    public static String mediaType(String path) {
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

    /** Formats a Duration as m:ss ("0:12", "1:05"). Null-safe: returns "0:00". */
    private static String formatSegmentTime(Duration d) {
        if (d == null) return "0:00";
        int totalSeconds = (int) d.toSeconds();
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }
}
