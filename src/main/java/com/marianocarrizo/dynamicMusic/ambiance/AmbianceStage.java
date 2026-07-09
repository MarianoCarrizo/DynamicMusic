package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Secondary JavaFX Stage that projects mood-reactive visuals onto a second
 * monitor or a floating window.
 *
 * Background (image/video/GIF) and particle effects are now independent:
 *  - setBackground(path) controls the media layer for the entire session.
 *  - setMood(type)        only changes which particle effect plays.
 *  - setAmplitude(amp)    feeds audio-reactive data to the particle system.
 *  - applyScreen(index)   moves the window to the given screen index live,
 *                         rebuilding the Stage if the decoration style changes.
 */
public class AmbianceStage {

    private static final Map<TrackType, Color> MOOD_COLORS = Map.of(
            TrackType.PAUSED,    Color.web("#151517"),
            TrackType.NORMAL,    Color.web("#0e1410"),
            TrackType.RAGED,     Color.web("#160e0e"),
            TrackType.ADVANTAGE, Color.web("#0f1318")
    );

    // Extensiones de imagen estática (con partículas); bmp incluido junto con los demás
    private static final Set<String> STATIC_IMAGE_EXT = Set.of("png", "jpg", "jpeg", "bmp");
    // Extensiones de video/gif (sin partículas)
    private static final Set<String> VIDEO_EXT        = Set.of("mp4", "avi");
    // Extensiones aceptadas por DnD (unión de imagen + gif; video excluido del drop)
    private static final Set<String> ACCEPTED_BG_EXT  = Set.of("png", "jpg", "jpeg", "bmp", "gif");

    // Nodos del scene graph — no finales para permitir rebuildStage()
    private Stage     stage;
    private StackPane root;
    private Scene     scene;
    private ImageView imageView;
    private MediaView mediaView;
    private Canvas    particleCanvas;

    private MediaPlayer    currentPlayer;
    private ParticleSystem currentEffect;

    private TrackType currentMood = TrackType.NORMAL;
    private volatile double amplitude  = 0.0;
    private volatile double sensitivity = 2.0;

    // true mientras el fondo permite que se rendericen partículas
    private boolean backgroundAllowsParticles = true;

    // Preferencias de efecto por mood; defaults en el constructor
    private final Map<TrackType, EffectType> moodEffects = new EnumMap<>(TrackType.class);

    // Estado de decoración actual (null = ventana no abierta todavía)
    private StageStyle currentStyle = null;

    // Índice de pantalla pendiente cuando applyScreen() se llama antes de open()
    private int pendingScreenIndex = -1;

    // Ruta del fondo actual para re-aplicar tras rebuildStage()
    private String currentBackgroundPath = null;

    // Callback invocado cuando el usuario arrastra y suelta un archivo de imagen
    private Consumer<String> onBackgroundDropped = null;

    public AmbianceStage() {
        moodEffects.put(TrackType.RAGED,     EffectType.FIRE);
        moodEffects.put(TrackType.NORMAL,    EffectType.AURORA);
        moodEffects.put(TrackType.ADVANTAGE, EffectType.ELECTRIC);
        moodEffects.put(TrackType.PAUSED,    EffectType.VOID);

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
        particleCanvas = new Canvas();

        root.getChildren().addAll(imageView, mediaView);

        imageView.setPreserveRatio(false);
        imageView.fitWidthProperty().bind(scene.widthProperty());
        imageView.fitHeightProperty().bind(scene.heightProperty());

        mediaView.fitWidthProperty().bind(scene.widthProperty());
        mediaView.fitHeightProperty().bind(scene.heightProperty());

        imageView.setVisible(false);
        mediaView.setVisible(false);

        particleCanvas.setMouseTransparent(true);
        particleCanvas.widthProperty().bind(scene.widthProperty());
        particleCanvas.heightProperty().bind(scene.heightProperty());
        root.getChildren().add(particleCanvas);

        root.setBackground(javafx.scene.layout.Background.EMPTY);
        scene.setFill(Color.BLACK);
        stage.setScene(scene);
        stage.setTitle("Dynamic Music — Ambiance");

        // Handlers de drag-and-drop: adjuntados aquí para que sobrevivan rebuildStage()
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

        setMood(currentMood);
        stage.show();
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
     * re-aplica todo el estado visual: fondo, mood, efecto, alwaysOnTop y DnD.
     */
    private void rebuildStage(StageStyle newStyle, Screen target) {
        // 1. Detener efectos y media del stage viejo
        stopParticleEffect();
        disposePlayer();

        // 2. Desligar bindings del scene graph viejo
        imageView.fitWidthProperty().unbind();
        imageView.fitHeightProperty().unbind();
        mediaView.fitWidthProperty().unbind();
        mediaView.fitHeightProperty().unbind();
        particleCanvas.widthProperty().unbind();
        particleCanvas.heightProperty().unbind();

        // 3. Cerrar el stage viejo
        stage.close();

        // 4. Crear un stage nuevo
        stage = new Stage();

        // 5. Reconstruir el scene graph y adjuntar al stage nuevo
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

        // 8. Mostrar
        stage.show();
    }

    /**
     * Re-aplica el estado visual completo al stage/scene recién construido.
     * Incluye fondo y mood actual. La sensitivity no requiere re-aplicación
     * explícita porque el campo volatile retiene el último valor escrito por
     * setSensitivity(); el nuevo scene graph lo usa directamente.
     */
    private void applyCurrentStateToNewStage() {
        if (currentBackgroundPath != null) {
            setBackground(currentBackgroundPath);
        }
        setMood(currentMood);
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

    /**
     * Sets a single background for the whole window:
     *  - Video (MP4/AVI) or GIF: played/shown without particles.
     *  - Static image (PNG/JPG/JPEG/BMP): shown with particles on top.
     *  - null or missing file: solid black background + particles.
     */
    public void setBackground(String path) {
        currentBackgroundPath = path;

        if (path == null) {
            clearMedia();
            backgroundAllowsParticles = true;
            ensureParticlesRunning();
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            clearMedia();
            backgroundAllowsParticles = true;
            ensureParticlesRunning();
            return;
        }
        try {
            String e = ext(path);
            if (isVideo(e)) {
                stopParticleEffect();
                backgroundAllowsParticles = false;
                setVideo(f);
            } else if ("gif".equals(e)) {
                stopParticleEffect();
                backgroundAllowsParticles = false;
                setImage(f);
            } else if (isStaticImage(e)) {
                backgroundAllowsParticles = true;
                setImage(f);
                ensureParticlesRunning();
            } else {
                clearMedia();
                backgroundAllowsParticles = true;
                ensureParticlesRunning();
            }
        } catch (Exception ex) {
            clearMedia();
            backgroundAllowsParticles = true;
            ensureParticlesRunning();
        }
    }

    // --- Mood (only changes particle effect, never touches the background) ---

    public void setMood(TrackType type) {
        if (type == null) return;
        currentMood = type;
        setColor(MOOD_COLORS.getOrDefault(type, Color.BLACK));
        if (backgroundAllowsParticles) {
            stopParticleEffect();
            startParticleEffect(moodEffects.getOrDefault(type, EffectType.AURORA));
        }
    }

    // --- Per-mood effect override ---

    public void setMoodEffect(TrackType mood, EffectType effect) {
        if (mood == null || effect == null) return;
        EffectType previous = moodEffects.put(mood, effect);
        if (mood == currentMood && backgroundAllowsParticles && effect != previous) {
            stopParticleEffect();
            startParticleEffect(effect);
        }
    }

    public EffectType getMoodEffect(TrackType type) {
        return moodEffects.getOrDefault(type, EffectType.AURORA);
    }

    // --- Amplitude (written from audio thread, read by ParticleSystem on FX thread) ---

    public void setAmplitude(double amp) {
        this.amplitude = Math.min(1.0, amp * sensitivity);
    }

    public void setSensitivity(double s) {
        this.sensitivity = Math.max(0.1, s);
    }

    public double getAmplitude() {
        return amplitude;
    }

    // --- Internal helpers ---

    private void ensureParticlesRunning() {
        if (currentEffect == null) {
            startParticleEffect(moodEffects.getOrDefault(currentMood, EffectType.AURORA));
        }
    }

    private void setColor(Color color) {
        scene.setFill(color);
    }

    private void disposePlayer() {
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
        }
    }

    private void clearMedia() {
        disposePlayer();
        imageView.setImage(null);
        imageView.setVisible(false);
        mediaView.setVisible(false);
    }

    private void setImage(File f) {
        disposePlayer();
        mediaView.setVisible(false);
        Image img = new Image(f.toURI().toString(), true);
        imageView.setImage(img);
        imageView.setVisible(true);
    }

    private void setVideo(File f) {
        imageView.setVisible(false);
        imageView.setImage(null);
        disposePlayer();
        Media media = new Media(f.toURI().toString());
        currentPlayer = new MediaPlayer(media);
        currentPlayer.setCycleCount(MediaPlayer.INDEFINITE);
        currentPlayer.setMute(true);
        mediaView.setMediaPlayer(currentPlayer);
        mediaView.setVisible(true);
        currentPlayer.setOnError(this::onMediaError);
        currentPlayer.play();
    }

    private void onMediaError() {
        clearMedia();
        backgroundAllowsParticles = true;
        ensureParticlesRunning();
    }

    private void startParticleEffect(EffectType effect) {
        currentEffect = new ParticleSystem(particleCanvas, effect, this::getAmplitude);
        currentEffect.start();
    }

    private void stopParticleEffect() {
        if (currentEffect != null) {
            currentEffect.stop();
            currentEffect = null;
        }
    }

    // --- Dispose ---

    public void dispose() {
        stopParticleEffect();
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
