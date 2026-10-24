package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.model.AuraSlot;
import com.marianocarrizo.dynamicMusic.service.AudioFeatures;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Secondary JavaFX Stage that projects mood-reactive visuals onto a second
 * monitor or a floating window.
 *
 * Background (image/video/GIF) and the aura overlay are independent:
 *  - setBackground(path)   controls the media layer for the entire session.
 *  - setAudioSource(...)   tells the aura where to read audio features from.
 *  - setAuras(map) / setAura(slot, cfg) store the scene's auras; setActiveSlot(slot) picks the
 *                          one on screen (a mood slot, or AuraSlot.ON_DEMAND while an on-demand
 *                          track plays; the caller switches back when it ends).
 *  - applyScreen(index)    moves the window to the given screen index live,
 *                          rebuilding the Stage if the decoration style changes.
 * The aura is drawn over images and videos alike and stops rendering while the window is hidden.
 */
public class AmbianceStage {

    // Developer diagnostics switch; see the "debug" profile in pom.xml
    private static final boolean DEBUG_MODE = Boolean.getBoolean("dynamicmusic.debug");

    // Extensiones de imagen estática; bmp incluido junto con los demás
    private static final Set<String> STATIC_IMAGE_EXT = Set.of("png", "jpg", "jpeg", "bmp");
    // Extensiones de video
    private static final Set<String> VIDEO_EXT        = Set.of("mp4", "avi");
    // Extensiones aceptadas por DnD (unión de imagen + gif; video excluido del drop)
    private static final Set<String> ACCEPTED_BG_EXT  = Set.of("png", "jpg", "jpeg", "bmp", "gif");

    // Nodos del scene graph — no finales para permitir rebuildStage()
    private Stage     stage;
    private StackPane root;
    private Scene     scene;
    private ImageView imageView;
    private MediaView mediaView;
    private Canvas             auraCanvas;
    // Indicador de carga de video; siempre sobre auraCanvas (índice 3 en el StackPane)
    private ProgressIndicator  loadingIndicator;

    private MediaPlayer currentPlayer;

    // Aura overlay: one renderer for the stage's lifetime, re-attached to the canvas on rebuildStage()
    private final AuraRenderer auraRenderer = new AuraRenderer(this::applyBackgroundPulse);
    private final Map<AuraSlot, AuraConfig> auras = new EnumMap<>(AuraSlot.class);
    private AuraSlot activeSlot = AuraSlot.NORMAL;
    // Shared by the background nodes while the beat pulse is active; detached when brightness is 0
    private final ColorAdjust pulseAdjust = new ColorAdjust();

    // Estado de decoración actual (null = ventana no abierta todavía)
    private StageStyle currentStyle = null;

    // Índice de pantalla pendiente cuando applyScreen() se llama antes de open()
    private int pendingScreenIndex = -1;

    // Ruta del fondo actual para re-aplicar tras rebuildStage()
    private String currentBackgroundPath = null;

    // Modo de ajuste del fondo; FILL por defecto (cubre sin barras, preserva aspecto)
    private BackgroundFitMode currentFitMode = BackgroundFitMode.FILL;

    // Callback invocado cuando el usuario arrastra y suelta un archivo de imagen
    private Consumer<String> onBackgroundDropped = null;

    // Ventana de loop A→B; null = video completo (desde ZERO hasta duración del media)
    private Duration segmentStart = null;
    private Duration segmentEnd   = null;
    // Callback del controlador: se dispara en cada transición READY de video en el hilo FX
    private Runnable onVideoReady = null;

    // Velocidad de reproducción del video; 1.0 = normal; rango [0.25, 2.0]
    private double videoRate = 1.0;

    // Estado de boomerang: cuando está activo, setVideo reproduce el archivo bakeado en lugar del original
    private boolean boomerangActive = false;
    private String  bakedVideoPath  = null;

    // Visibilidad elegida por el usuario; false = ventana oculta (aura detenida, video pausado)
    private boolean visible = true;
    // Callback del controlador: se dispara en el hilo FX cada vez que cambia la visibilidad
    private Consumer<Boolean> onVisibilityChanged = null;

    // Executor de inicialización de media: mueve new Media(uri) fuera del hilo FX
    private final ExecutorService mediaInit = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-init");
        t.setDaemon(true);
        return t;
    });
    // Contador de generación: protege callbacks Platform.runLater contra media obsoleto
    // Solo se accede desde el hilo FX; no necesita volatile
    private int mediaGeneration = 0;

    public AmbianceStage() {
        stage = new Stage();
        buildScene();
    }

    // --- Construcción/reconstrucción del scene graph ---

    /**
     * Construye el scene graph y lo asocia al stage actual.
     * Puede llamarse de nuevo en rebuildStage() para producir un grafo nuevo
     * sin duplicar listeners (el stage viejo ya está cerrado antes de la llamada).
     */
    private void buildScene() {
        root          = new StackPane();
        scene         = new Scene(root);
        imageView     = new ImageView();
        mediaView     = new MediaView();
        auraCanvas    = new Canvas();

        root.getChildren().addAll(imageView, mediaView);

        imageView.setPreserveRatio(false);
        imageView.fitWidthProperty().bind(scene.widthProperty());
        imageView.fitHeightProperty().bind(scene.heightProperty());

        mediaView.fitWidthProperty().bind(scene.widthProperty());
        mediaView.fitHeightProperty().bind(scene.heightProperty());

        imageView.setVisible(false);
        mediaView.setVisible(false);

        auraCanvas.setMouseTransparent(true);
        auraCanvas.widthProperty().bind(scene.widthProperty());
        auraCanvas.heightProperty().bind(scene.heightProperty());
        root.getChildren().add(auraCanvas);
        auraRenderer.attach(auraCanvas);

        // Overlay de carga de video: índice 3 (sobre auraCanvas), siempre mouse-transparent
        // Estilo inline obligatorio: el scene de ambiance no tiene stylesheet asociado
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMouseTransparent(true);
        loadingIndicator.setStyle(
                "-fx-progress-color: #7ecfff;" +
                "-fx-background-color: transparent;"
        );
        loadingIndicator.setVisible(false);
        root.getChildren().add(loadingIndicator);

        root.setBackground(javafx.scene.layout.Background.EMPTY);
        scene.setFill(Color.BLACK);
        stage.setScene(scene);
        // Cerrar la ventana (X / Alt+F4) solo la oculta; se puede volver a abrir desde el controlador
        stage.setOnCloseRequest(e -> {
            e.consume();
            hide();
        });
        stage.setTitle("Dynamic Music — Ambiance");

        // Handlers de drag-and-drop: adjuntados aquí para que sobrevivan rebuildStage()
        // F3 toggles the aura diagnostics HUD (and its CSV recording), only in debug mode
        // (-Ddynamicmusic.debug=true, set by the Maven "debug" profile); attached here to survive rebuildStage()
        scene.setOnKeyPressed(event -> {
            if (DEBUG_MODE && event.getCode() == javafx.scene.input.KeyCode.F3) {
                auraRenderer.setDebug(!auraRenderer.isDebug());
                event.consume();
            }
        });

        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                List<File> files = event.getDragboard().getFiles();
                if (!files.isEmpty() && isAcceptedBgExt(ext(files.get(0).getName()))) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
            }
            event.consume();
        });

        scene.setOnDragDropped(event -> {
            boolean success = false;
            if (event.getDragboard().hasFiles()) {
                List<File> files = event.getDragboard().getFiles();
                if (!files.isEmpty()) {
                    File f = files.get(0);
                    if (isAcceptedBgExt(ext(f.getName()))) {
                        String path = f.getAbsolutePath();
                        setBackground(path);
                        if (onBackgroundDropped != null) {
                            onBackgroundDropped.accept(path);
                        }
                        success = true;
                    }
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });

        // Clip incondicional sobre el root: evita que CENTER desborde fuera del área de la escena
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(scene.widthProperty());
        clip.heightProperty().bind(scene.heightProperty());
        root.setClip(clip);

        // Listener de redimensionado: recomputa el viewport FILL si está activo
        scene.widthProperty().addListener((obs, o, n) -> recomputeFillViewportIfActive());
        scene.heightProperty().addListener((obs, o, n) -> recomputeFillViewportIfActive());
    }

    // --- Apertura del stage ---

    /**
     * Abre el stage por primera vez. Las llamadas subsiguientes son no-op.
     */
    public void open() {
        if (currentStyle != null) return; // ya abierto

        Screen target = resolveScreen(pendingScreenIndex);
        boolean wantsUndecorated = isNonPrimaryMultiMonitor(target);
        StageStyle style = wantsUndecorated ? StageStyle.UNDECORATED : StageStyle.DECORATED;

        stage.initStyle(style);
        positionStage(target, wantsUndecorated);
        stage.setAlwaysOnTop(wantsUndecorated);
        stage.setFullScreen(false);
        currentStyle = style;

        stage.show();
        auraRenderer.start();
        notifyVisibility();
    }

    // --- Mostrar / ocultar a demanda ---

    /**
     * Muestra la ventana de ambiance. Si nunca se abrió, delega en open().
     * Si ya está visible, la trae al frente.
     */
    public void show() {
        visible = true;
        if (currentStyle == null) {
            open();
            return;
        }
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        if (currentPlayer != null) currentPlayer.play();
        stage.show();
        auraRenderer.start();
        notifyVisibility();
    }

    /**
     * Oculta la ventana sin perder estado (fondo, aura, segmento). Detiene el
     * render del aura y pausa el video para no consumir CPU mientras no se ve.
     */
    public void hide() {
        if (!visible) return;
        visible = false;
        auraRenderer.stop();
        if (currentPlayer != null) currentPlayer.pause();
        stage.hide();
        notifyVisibility();
    }

    public boolean isVisible() {
        return visible && stage.isShowing();
    }

    public void setOnVisibilityChanged(Consumer<Boolean> callback) {
        this.onVisibilityChanged = callback;
    }

    private void notifyVisibility() {
        if (onVisibilityChanged != null) onVisibilityChanged.accept(isVisible());
    }

    // --- Selección de pantalla en vivo ---

    /**
     * Mueve el stage a la pantalla indicada por {@code index}.
     * Si el cambio implica cruzar la frontera DECORATED⟷UNDECORATED, reconstruye el Stage.
     * Si se llama antes de open(), guarda el índice para aplicarlo al abrir.
     */
    public void applyScreen(int index) {
        pendingScreenIndex = index;

        if (currentStyle == null) {
            // Ventana no abierta todavía; el índice se usa en open()
            return;
        }

        Screen target          = resolveScreen(index);
        boolean wantsUndecorated = isNonPrimaryMultiMonitor(target);
        StageStyle wantedStyle = wantsUndecorated ? StageStyle.UNDECORATED : StageStyle.DECORATED;

        if (wantedStyle == currentStyle) {
            // Mismo estilo — solo reposicionar
            positionStage(target, wantsUndecorated);
            stage.setAlwaysOnTop(wantsUndecorated);
        } else {
            // Cruce de frontera — reconstruir el Stage
            rebuildStage(wantedStyle, target);
        }
    }

    // --- Reconstrucción del Stage (cruce DECORATED↔UNDECORATED) ---

    /**
     * Cierra el stage actual, construye uno nuevo con el estilo indicado y
     * re-aplica todo el estado visual: fondo, aura, alwaysOnTop y DnD.
     */
    private void rebuildStage(StageStyle newStyle, Screen target) {
        // 1. Detener aura y media del stage viejo
        auraRenderer.stop(); // also resets the background pulse
        imageView.setEffect(null);
        mediaView.setEffect(null);
        disposePlayer();

        // 2. Desligar bindings del scene graph viejo
        imageView.fitWidthProperty().unbind();
        imageView.fitHeightProperty().unbind();
        mediaView.fitWidthProperty().unbind();
        mediaView.fitHeightProperty().unbind();
        auraCanvas.widthProperty().unbind();
        auraCanvas.heightProperty().unbind();

        // 3. Cerrar el stage viejo
        stage.close();

        // 4. Crear un stage nuevo
        stage = new Stage();

        // 5. Reconstruir el scene graph y adjuntar al stage nuevo (buildScene re-adjunta el aura al canvas nuevo)
        buildScene();

        // 6. Aplicar estilo y posición
        stage.initStyle(newStyle);
        boolean undecorated = (newStyle == StageStyle.UNDECORATED);
        positionStage(target, undecorated);
        stage.setAlwaysOnTop(undecorated);
        stage.setFullScreen(false);
        currentStyle = newStyle;

        // 7. Re-aplicar estado visual
        applyCurrentStateToNewStage();

        // 8. Mostrar (solo si el usuario no la ocultó)
        if (visible) {
            stage.show();
            auraRenderer.start();
        }
    }

    /**
     * Re-aplica el estado visual al stage/scene recién construido. Solo el fondo:
     * el renderer del aura conserva su look y su fuente de audio, y el pulso de fondo
     * se resuelve contra los nodos actuales en cada frame.
     */
    private void applyCurrentStateToNewStage() {
        if (currentBackgroundPath != null) {
            setBackground(currentBackgroundPath);
        }
    }

    // --- Resolución de pantalla ---

    /**
     * Resuelve la pantalla objetivo según el índice dado.
     * Índice válido → devuelve esa pantalla.
     * Índice inválido o -1 → auto: segunda pantalla si hay ≥2, si no la primaria.
     */
    private Screen resolveScreen(int index) {
        List<Screen> screens = Screen.getScreens();
        if (index >= 0 && index < screens.size()) {
            return screens.get(index);
        }
        // Fallback auto
        return screens.size() >= 2 ? screens.get(1) : Screen.getPrimary();
    }

    /**
     * Devuelve true si la pantalla es una pantalla no-primaria en una
     * configuración multi-monitor, lo que implica usar UNDECORATED.
     */
    private boolean isNonPrimaryMultiMonitor(Screen target) {
        return Screen.getScreens().size() >= 2 && !target.equals(Screen.getPrimary());
    }

    /**
     * Posiciona y dimensiona el stage según la pantalla y el modo de decoración.
     */
    private void positionStage(Screen target, boolean undecorated) {
        if (undecorated) {
            Rectangle2D b = target.getBounds();
            stage.setX(b.getMinX());
            stage.setY(b.getMinY());
            stage.setWidth(b.getWidth());
            stage.setHeight(b.getHeight());
        } else {
            Rectangle2D b = Screen.getPrimary().getVisualBounds();
            stage.setWidth(b.getWidth() * 0.6);
            stage.setHeight(b.getHeight() * 0.6);
            stage.centerOnScreen();
        }
    }

    // --- Callback para drag-and-drop ---

    /**
     * Registra el callback que se invoca cuando el usuario suelta un archivo
     * de imagen válido sobre el stage de ambiance.
     * El callback recibe la ruta absoluta del archivo.
     */
    public void setOnBackgroundDropped(Consumer<String> callback) {
        this.onBackgroundDropped = callback;
    }

    // --- Background (session-wide, independent of mood) ---

    /** Limpia el estado de boomerang. Llamado en todos los sites no-video de setBackground. */
    private void clearBoomerangState() {
        boomerangActive = false;
        bakedVideoPath  = null;
    }

    /**
     * Limpia el estado boomerang sin re-reproducir el fondo.
     * Para llamadores que invocan setBackground inmediatamente después
     * (el branch de video confía en el estado que fijó el llamador).
     */
    public void resetBoomerang() {
        clearBoomerangState();
    }

    /**
     * Sets a single background for the whole window:
     *  - Video (MP4/AVI) or GIF: played/shown, aura on top.
     *  - Static image (PNG/JPG/JPEG/BMP): shown, aura on top.
     *  - null or missing file: solid black background + aura.
     */
    public void setBackground(String path) {
        currentBackgroundPath = path;

        if (path == null) {
            clearBoomerangState();
            segmentStart = null;
            segmentEnd   = null;
            clearMedia();
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            clearBoomerangState();
            segmentStart = null;
            segmentEnd   = null;
            clearMedia();
            return;
        }
        try {
            String e = ext(path);
            if (isVideo(e)) {
                // No nulificar segmento ni boomerang: el llamador los estableció antes de setBackground durante restore
                setVideo(f);
            } else if ("gif".equals(e)) {
                clearBoomerangState();
                segmentStart = null;
                segmentEnd   = null;
                setImage(f);
            } else if (isStaticImage(e)) {
                clearBoomerangState();
                segmentStart = null;
                segmentEnd   = null;
                setImage(f);
            } else {
                clearBoomerangState();
                segmentStart = null;
                segmentEnd   = null;
                clearMedia();
            }
        } catch (Exception ex) {
            clearBoomerangState();
            segmentStart = null;
            segmentEnd   = null;
            clearMedia();
        }
    }

    // --- Aura (never touches the background media, only the overlay and a subtle pulse on it) ---

    /** Where the aura reads audio features from; polled every rendered frame on the FX thread. */
    public void setAudioSource(Supplier<AudioFeatures> source) {
        auraRenderer.setAudioSource(source);
    }

    /**
     * Replaces the stored auras (typically the active scene's) and moves the visible aura to
     * them with the long (~1.5 s) crossfade. The configs are kept by reference.
     */
    public void setAuras(Map<AuraSlot, AuraConfig> newAuras) {
        auras.clear();
        if (newAuras != null) auras.putAll(newAuras);
        showActiveAura(true);
    }

    /**
     * Stores the aura of one slot. If that slot is the visible one the change is applied with
     * a short (~0.2 s) blend, so live edits feel immediate; other slots are just remembered.
     */
    public void setAura(AuraSlot slot, AuraConfig aura) {
        if (slot == null || aura == null) return;
        auras.put(slot, aura);
        if (slot == activeSlot) showActiveAura(false);
    }

    /**
     * Chooses which slot's aura is on screen and crossfades to it (long transition).
     * Use a mood slot normally; step 4 calls {@code setActiveSlot(AuraSlot.ON_DEMAND)} when an
     * on-demand track starts and switches back to the mood slot when it ends.
     */
    public void setActiveSlot(AuraSlot slot) {
        if (slot == null) return;
        activeSlot = slot;
        showActiveAura(true);
    }

    public AuraSlot getActiveSlot() {
        return activeSlot;
    }

    private void showActiveAura(boolean longTransition) {
        AuraConfig aura = auras.get(activeSlot);
        // PAUSED is the calm mood: always softer than the others, whatever preset it uses
        if (aura != null) auraRenderer.setTarget(aura, longTransition, activeSlot == AuraSlot.PAUSED);
    }

    /**
     * Called by the renderer every frame the pulse changes. Scales both background nodes (only
     * one is visible) around their center and lifts brightness; the effect is attached only
     * while brightness is non-zero so video is not rendered offscreen the rest of the time.
     * Reads the node fields on each call, so it keeps working after rebuildStage().
     */
    private void applyBackgroundPulse(double scale, double brightness) {
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);
        mediaView.setScaleX(scale);
        mediaView.setScaleY(scale);
        if (brightness > 0.002) {
            pulseAdjust.setBrightness(brightness);
            if (imageView.getEffect() != pulseAdjust) imageView.setEffect(pulseAdjust);
            if (mediaView.getEffect() != pulseAdjust) mediaView.setEffect(pulseAdjust);
        } else {
            if (imageView.getEffect() != null) imageView.setEffect(null);
            if (mediaView.getEffect() != null) mediaView.setEffect(null);
        }
    }

    // --- Fit mode (public API) ---

    /**
     * Aplica el modo de ajuste del fondo al view visible actualmente.
     * Null-safe: si mode es null, no hace nada.
     */
    public void setBackgroundFitMode(BackgroundFitMode mode) {
        if (mode == null) return;
        this.currentFitMode = mode;
        applyFitModeToCurrentView();
    }

    // --- Fit mode (internos) ---

    /**
     * Despacha la aplicación del modo de ajuste al view que esté visible y listo.
     */
    private void applyFitModeToCurrentView() {
        if (imageView.isVisible() && imageView.getImage() != null) {
            applyFitToImageView();
        } else if (mediaView.isVisible() && currentPlayer != null) {
            applyFitToMediaView();
        }
    }

    /**
     * Aplica {@code currentFitMode} al ImageView.
     * Siempre desvincula fitWidth/fitHeight antes de reasignar (regla unbind-first).
     */
    private void applyFitToImageView() {
        imageView.fitWidthProperty().unbind();
        imageView.fitHeightProperty().unbind();

        switch (currentFitMode) {
            case STRETCH -> {
                imageView.setPreserveRatio(false);
                imageView.fitWidthProperty().bind(scene.widthProperty());
                imageView.fitHeightProperty().bind(scene.heightProperty());
                imageView.setViewport(null);
            }
            case FIT -> {
                imageView.setPreserveRatio(true);
                imageView.fitWidthProperty().bind(scene.widthProperty());
                imageView.fitHeightProperty().bind(scene.heightProperty());
                imageView.setViewport(null);
            }
            case FILL -> {
                imageView.setPreserveRatio(false);
                imageView.fitWidthProperty().bind(scene.widthProperty());
                imageView.fitHeightProperty().bind(scene.heightProperty());
                Image img = imageView.getImage();
                if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                    imageView.setViewport(computeFillViewport(
                            img.getWidth(), img.getHeight(),
                            scene.getWidth(), scene.getHeight()));
                }
                // Si la imagen aún no tiene dimensiones (carga async), el listener
                // de widthProperty en setImage() aplicará el viewport cuando esté listo.
            }
            case CENTER -> {
                imageView.setPreserveRatio(false);
                imageView.setFitWidth(0);
                imageView.setFitHeight(0);
                imageView.setViewport(null);
            }
        }
    }

    /**
     * Aplica {@code currentFitMode} al MediaView.
     * Siempre desvincula fitWidth/fitHeight antes de reasignar (regla unbind-first).
     */
    private void applyFitToMediaView() {
        if (currentPlayer == null) return;
        mediaView.fitWidthProperty().unbind();
        mediaView.fitHeightProperty().unbind();

        switch (currentFitMode) {
            case STRETCH -> {
                mediaView.setPreserveRatio(false);
                mediaView.fitWidthProperty().bind(scene.widthProperty());
                mediaView.fitHeightProperty().bind(scene.heightProperty());
                mediaView.setViewport(null);
            }
            case FIT -> {
                mediaView.setPreserveRatio(true);
                mediaView.fitWidthProperty().bind(scene.widthProperty());
                mediaView.fitHeightProperty().bind(scene.heightProperty());
                mediaView.setViewport(null);
            }
            case FILL -> {
                mediaView.setPreserveRatio(false);
                mediaView.fitWidthProperty().bind(scene.widthProperty());
                mediaView.fitHeightProperty().bind(scene.heightProperty());
                double natW = currentPlayer.getMedia().getWidth();
                double natH = currentPlayer.getMedia().getHeight();
                if (natW > 0 && natH > 0) {
                    mediaView.setViewport(computeFillViewport(
                            natW, natH, scene.getWidth(), scene.getHeight()));
                }
                // Si las dimensiones aún no están disponibles (estado pre-READY),
                // el setOnReady extendido en setVideo() las aplicará al alcanzar READY.
            }
            case CENTER -> {
                mediaView.setPreserveRatio(false);
                mediaView.setFitWidth(0);
                mediaView.setFitHeight(0);
                mediaView.setViewport(null);
            }
        }
    }

    /**
     * Calcula el viewport Rectangle2D para el modo FILL.
     * Retorna null si alguna dimensión es <= 0 (señal de pendiente).
     *
     * <p>Fórmula: scale = max(sceneW/natW, sceneH/natH); viewW = sceneW/scale;
     * viewH = sceneH/scale; x = (natW-viewW)/2; y = (natH-viewH)/2.
     */
    private Rectangle2D computeFillViewport(double natW, double natH,
                                            double sceneW, double sceneH) {
        if (natW <= 0 || natH <= 0 || sceneW <= 0 || sceneH <= 0) return null;
        double scale = Math.max(sceneW / natW, sceneH / natH);
        double viewW = sceneW / scale;
        double viewH = sceneH / scale;
        double x = (natW - viewW) / 2.0;
        double y = (natH - viewH) / 2.0;
        return new Rectangle2D(x, y, viewW, viewH);
    }

    /**
     * Recomputa el viewport FILL en el view visible si el modo activo es FILL.
     * No-op para los demás modos (STRETCH/FIT/CENTER no necesitan recálculo en resize).
     */
    private void recomputeFillViewportIfActive() {
        if (currentFitMode != BackgroundFitMode.FILL) return;
        if (imageView.isVisible() && imageView.getImage() != null) {
            applyFitToImageView();
        } else if (mediaView.isVisible() && currentPlayer != null) {
            applyFitToMediaView();
        }
    }

    // --- Internal helpers ---

    private void disposePlayer() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
        }
    }

    private void clearMedia() {
        mediaGeneration++; // invalida cualquier callback de video pendiente
        disposePlayer();
        loadingIndicator.setVisible(false);
        imageView.setImage(null);
        imageView.setVisible(false);
        mediaView.setVisible(false);
    }

    private void setImage(File f) {
        mediaGeneration++; // invalida cualquier callback de video pendiente (transición video→imagen)
        disposePlayer();
        loadingIndicator.setVisible(false);
        mediaView.setVisible(false);
        Image img = new Image(f.toURI().toString(), true);
        imageView.setImage(img);
        imageView.setVisible(true);
        // Aplicar modo de ajuste de inmediato; para FILL con imagen async las dimensiones
        // pueden ser 0 todavía — el listener de width las aplica cuando estén disponibles.
        applyFitToImageView();
        if (currentFitMode == BackgroundFitMode.FILL) {
            // Listener de un solo disparo: aplica el viewport FILL cuando la imagen termine de cargar
            javafx.beans.value.ChangeListener<Number>[] listenerRef = new javafx.beans.value.ChangeListener[1];
            listenerRef[0] = (obs, oldW, newW) -> {
                if (newW.doubleValue() > 0 && imageView.getImage() == img) {
                    // Re-verificar el modo: pudo cambiar mientras la imagen cargaba
                    if (currentFitMode == BackgroundFitMode.FILL) {
                        imageView.setViewport(computeFillViewport(
                                img.getWidth(), img.getHeight(),
                                scene.getWidth(), scene.getHeight()));
                    }
                    img.widthProperty().removeListener(listenerRef[0]);
                }
            };
            img.widthProperty().addListener(listenerRef[0]);
        }
    }

    private void setVideo(File f) {
        // Cuando el boomerang está activo, reproducir el archivo bakeado en lugar del original
        File toPlay = (boomerangActive && bakedVideoPath != null) ? new File(bakedVideoPath) : f;

        // Incrementar generación ANTES de cualquier trabajo en background
        final int gen = ++mediaGeneration;

        imageView.setVisible(false);
        imageView.setImage(null);
        disposePlayer();                   // descartar el player anterior de forma síncrona
        mediaView.setVisible(true);
        loadingIndicator.setVisible(true); // spinner PRIMERO — pinta antes de cualquier llamada nativa

        final String uri = toPlay.toURI().toString();
        final boolean bakedActive = (boomerangActive && bakedVideoPath != null);

        mediaInit.submit(() -> {
            Media media;
            try {
                media = new Media(uri);    // sondeo nativo pesado — fuera del hilo FX
            } catch (Exception ex) {
                Platform.runLater(this::onMediaError);
                return;
            }
            // Volver al hilo FX para construir el player y asignarlo al scene graph
            Platform.runLater(() -> {
                // Guardia de generación: si el usuario cambió el fondo mientras el sondeo
                // corría, este callback es obsoleto — descartar sin tocar ningún estado
                if (gen != mediaGeneration) {
                    new MediaPlayer(media).dispose(); // evitar fuga del Media ya inicializado
                    return;
                }
                MediaPlayer player = new MediaPlayer(media);
                currentPlayer = player;
                player.setCycleCount(MediaPlayer.INDEFINITE);
                player.setMute(true);
                // Aplicar segmento pre-READY solo cuando el boomerang NO está activo
                if (!bakedActive && segmentStart != null && segmentEnd != null) {
                    player.setStartTime(segmentStart);
                    player.setStopTime(segmentEnd);
                }
                player.setRate(videoRate);
                // CRÍTICO: mediaView y loadingIndicator se leen como campos de instancia
                // al momento de ejecución — nunca capturados como locales antes del submit,
                // para que rebuildStage() pueda reasignar los campos sin romper este callback
                mediaView.setMediaPlayer(player);
                player.setOnError(this::onMediaError);
                // El Media ya viene parseado del hilo de fondo: el player puede llegar a READY
                // ANTES de registrar el handler, y setOnReady no se dispara retroactivamente.
                // Registrar y luego verificar el estado actual, con guardia de ejecución única.
                final boolean[] readyFired = { false };
                Runnable readyHandler = () -> {
                    if (readyFired[0]) return;
                    readyFired[0] = true;
                    // Doble guardia: identidad + generación (defensa en profundidad)
                    if (currentPlayer == player && gen == mediaGeneration) {
                        loadingIndicator.setVisible(false);
                        applyFitToMediaView();
                        // Re-afirmar segmento en READY cuando el boomerang NO está activo
                        if (!bakedActive && segmentStart != null && segmentEnd != null) {
                            player.setStartTime(segmentStart);
                            player.setStopTime(segmentEnd);
                        }
                        if (onVideoReady != null) onVideoReady.run();
                    }
                };
                player.setOnReady(readyHandler);
                if (visible) player.play(); // hidden: show() resumes playback
                applyFitToMediaView();
                if (player.getStatus() == MediaPlayer.Status.READY) readyHandler.run();
            });
        });
    }

    private void onMediaError() {
        loadingIndicator.setVisible(false);
        clearMedia();
    }

    // --- Segment loop API ---

    /**
     * Almacena la ventana A→B y la aplica en vivo si hay un player activo.
     * Solo guarda estructuralmente: start y end deben ser no-null y start < end.
     * La validación de brecha mínima (2s) es responsabilidad del llamador.
     */
    public void setVideoSegment(Duration start, Duration end) {
        if (start == null || end == null || !start.lessThan(end)) return;
        segmentStart = start;
        segmentEnd   = end;
        // Si el boomerang está activo, el archivo bakeado ES el segmento;
        // no mutar startTime/stopTime del player en vivo (se recalcula tras el swap).
        if (!boomerangActive && currentPlayer != null) {
            currentPlayer.setStartTime(start);
            currentPlayer.setStopTime(end);
        }
    }

    /**
     * Restablece el loop al video completo y nulifica los campos almacenados.
     * Si hay un player activo, re-afirma ZERO → duración del media
     * (guarda contra duración UNKNOWN/INDEFINITE).
     * Limpia el estado de boomerang como defensa en profundidad
     * (el controlador ya debería haberlo desactivado antes de llamar aquí).
     */
    public void clearVideoSegment() {
        clearBoomerangState();
        segmentStart = null;
        segmentEnd   = null;
        if (currentPlayer != null) {
            currentPlayer.setStartTime(Duration.ZERO);
            Duration mediaDuration = currentPlayer.getMedia().getDuration();
            if (mediaDuration != null && !mediaDuration.isUnknown() && !mediaDuration.isIndefinite()) {
                currentPlayer.setStopTime(mediaDuration);
            }
        }
    }

    /**
     * Retorna la posición de reproducción actual. Retorna Duration.ZERO cuando no hay player activo.
     */
    public Duration getCurrentVideoTime() {
        return currentPlayer != null ? currentPlayer.getCurrentTime() : Duration.ZERO;
    }

    /**
     * Establece la velocidad de reproducción del video.
     * El valor se clampea al rango [0.25, 2.0] y se aplica en vivo si hay un player activo.
     */
    public void setVideoRate(double rate) {
        videoRate = Math.max(0.25, Math.min(2.0, rate));
        if (currentPlayer != null) {
            currentPlayer.setRate(videoRate);
        }
    }

    // --- Boomerang playback API ---

    /**
     * Activa el boomerang: recarga el fondo usando el archivo bakeado indicado.
     * La velocidad de reproducción y el modo de ajuste se aplican normalmente via setVideo.
     *
     * @param bakedPath ruta absoluta del archivo MP4 bakeado (palíndromo A→B→A)
     */
    public void activateBoomerang(String bakedPath) {
        this.boomerangActive = true;
        this.bakedVideoPath  = bakedPath;
        if (currentBackgroundPath != null) {
            setBackground(currentBackgroundPath);
        }
    }

    /**
     * Desactiva el boomerang: recarga el fondo original con el segmento A→B normal.
     */
    public void deactivateBoomerang() {
        this.boomerangActive = false;
        this.bakedVideoPath  = null;
        if (currentBackgroundPath != null) {
            setBackground(currentBackgroundPath);
        }
    }

    /**
     * Retorna true si el boomerang está activo en este momento.
     */
    public boolean isBoomerangActive() {
        return boomerangActive;
    }

    /**
     * Registra el Runnable que se dispara en cada transición READY de video en el hilo FX.
     * Sigue el patrón de setOnBackgroundDropped.
     */
    public void setOnVideoReady(Runnable r) {
        this.onVideoReady = r;
    }

    /**
     * Retorna true cuando hay un MediaPlayer de video activo.
     * Guarda secundario para PlayerController; la puerta primaria es el string de tipo de media.
     */
    public boolean isVideoBackground() {
        return currentPlayer != null;
    }

    // --- Dispose ---

    public void dispose() {
        auraRenderer.stop();
        mediaInit.shutdownNow();
        disposePlayer();
        stage.close();
    }

    // --- Extension helpers ---

    private static String ext(String p) {
        int i = p.lastIndexOf('.');
        return i < 0 ? "" : p.substring(i + 1).toLowerCase();
    }

    private static boolean isStaticImage(String e) {
        return STATIC_IMAGE_EXT.contains(e);
    }

    private static boolean isVideo(String e) {
        return VIDEO_EXT.contains(e);
    }

    private static boolean isAcceptedBgExt(String e) {
        return ACCEPTED_BG_EXT.contains(e);
    }
}
