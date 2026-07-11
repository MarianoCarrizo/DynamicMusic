package com.marianocarrizo.dynamicMusic;

import com.marianocarrizo.dynamicMusic.Controller.PlayerController;
import com.marianocarrizo.dynamicMusic.ambiance.BoomerangBaker;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.util.HashSet;
import java.util.Set;

public class MusicServiceStarter extends Application {

    private double xOffset = 0;
    private double yOffset = 0;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/player_view.fxml"));
        Parent root = loader.load();

        stage.setTitle("Dynamic Music Player");

        // Ícono de la aplicación
        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.ico")));
        } catch (Exception e) {
            System.out.println("No se encontró icon.ico, se usará el ícono por defecto.");
        }

        // Estilo sin bordes nativos
        stage.initStyle(StageStyle.UNDECORATED);

        Scene scene = new Scene(root);
        // Forzamos transparencia real en la Scene
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);

        // Conectamos el archivo CSS
        try {
            scene.getStylesheets().add(getClass().getResource("/styles/dark-theme.css").toExternalForm());
        } catch (Exception e) {
            System.out.println("No se encontró el archivo CSS de estilos, se usará el diseño nativo.");
        }

        stage.setScene(scene);

        // Forzamos la aplicación de estilos antes de buscar el nodo de arrastre
        root.applyCss();
        root.layout();

        Node header = root.lookup(".window-header");

        if (header != null) {
            // Arrastre desde la barra superior (comportamiento esperado)
            header.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            header.setOnMouseDragged(event -> {
                if (stage.isMaximized()) return; // drag no hace nada mientras está maximizado
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            });
        } else {
            // Plan B: arrastre desde el contenedor principal si no hay header
            // NUNCA desde el 'root' de la Scene para no romper la transparencia nativa
            Node mainContainer = root.lookup(".main-container");
            if (mainContainer != null) {
                mainContainer.setOnMousePressed(event -> {
                    xOffset = event.getSceneX();
                    yOffset = event.getSceneY();
                });
                mainContainer.setOnMouseDragged(event -> {
                    stage.setX(event.getScreenX() - xOffset);
                    stage.setY(event.getScreenY() - yOffset);
                });
            }
        }

        // Captura de teclado numérico - 1, 2, 3, 4
        PlayerController controller = loader.getController();

        // Sondeo de ffmpeg: se inyecta ANTES de applySession para que la restauración
        // del boomerang sepa si ffmpeg está disponible antes de intentar un re-bake
        controller.setFfmpegAvailable(BoomerangBaker.isFfmpegAvailable());

        // Auto-carga de sesión guardada (G3): se aplica ANTES de stage.show()
        // para que la UI refleje el estado anterior desde el primer render
        SessionConfigManager sessionManager = new SessionConfigManager();
        SessionConfig savedSession = sessionManager.load();
        controller.applySession(savedSession);

        // Set de teclas actualmente presionadas para ignorar key-repeat del SO
        Set<KeyCode> heldKeys = new HashSet<>();

        scene.setOnKeyPressed(e -> {
            if (heldKeys.contains(e.getCode())) return; // ignorar repetición
            heldKeys.add(e.getCode());
            switch (e.getCode()) {
                case DIGIT1 -> controller.switchTrack(TrackType.PAUSED);
                case DIGIT2 -> controller.switchTrack(TrackType.NORMAL);
                case DIGIT3 -> controller.switchTrack(TrackType.RAGED);
                case DIGIT4 -> controller.switchTrack(TrackType.ADVANTAGE);
            }
        });

        scene.setOnKeyReleased(e -> heldKeys.remove(e.getCode()));

        addResizeSupport(stage, root);

        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();
    }

    private void addResizeSupport(Stage stage, Parent root) {
        final int B = 7; // ancho del borde de resize en píxeles
        final double[] drag = new double[6]; // [mouseX, mouseY, stageX, stageY, stageW, stageH]
        final boolean[] active = {false};
        final int[] dir = {0}; // bitmask: 1=N 2=S 4=W 8=E

        root.setOnMouseMoved(e -> {
            if (active[0]) return;
            if (stage.isMaximized()) { root.setCursor(Cursor.DEFAULT); return; }
            double x = e.getSceneX(), y = e.getSceneY();
            double w = stage.getWidth(), h = stage.getHeight();
            boolean n = y < B, s = y > h - B, west = x < B, east = x > w - B;
            int d = (n ? 1 : 0) | (s ? 2 : 0) | (west ? 4 : 0) | (east ? 8 : 0);
            dir[0] = d;
            root.setCursor(switch (d) {
                case 1  -> Cursor.N_RESIZE;
                case 2  -> Cursor.S_RESIZE;
                case 4  -> Cursor.W_RESIZE;
                case 8  -> Cursor.E_RESIZE;
                case 5  -> Cursor.NW_RESIZE;
                case 9  -> Cursor.NE_RESIZE;
                case 6  -> Cursor.SW_RESIZE;
                case 10 -> Cursor.SE_RESIZE;
                default -> Cursor.DEFAULT;
            });
        });

        root.setOnMousePressed(e -> {
            if (stage.isMaximized()) return;
            if (dir[0] == 0) return;
            active[0] = true;
            drag[0] = e.getScreenX(); drag[1] = e.getScreenY();
            drag[2] = stage.getX();   drag[3] = stage.getY();
            drag[4] = stage.getWidth(); drag[5] = stage.getHeight();
        });

        root.setOnMouseDragged(e -> {
            if (!active[0]) return;
            double dx = e.getScreenX() - drag[0];
            double dy = e.getScreenY() - drag[1];
            double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 400;
            double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 300;
            int d = dir[0];
            if ((d & 8) != 0) stage.setWidth(Math.max(minW, drag[4] + dx));
            if ((d & 4) != 0) { double nw = Math.max(minW, drag[4] - dx); stage.setX(drag[2] + drag[4] - nw); stage.setWidth(nw); }
            if ((d & 2) != 0) stage.setHeight(Math.max(minH, drag[5] + dy));
            if ((d & 1) != 0) { double nh = Math.max(minH, drag[5] - dy); stage.setY(drag[3] + drag[5] - nh); stage.setHeight(nh); }
        });

        root.setOnMouseReleased(e -> {
            active[0] = false;
            root.setCursor(Cursor.DEFAULT);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
