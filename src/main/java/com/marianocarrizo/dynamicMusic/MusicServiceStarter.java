package com.marianocarrizo.dynamicMusic;

import com.marianocarrizo.dynamicMusic.Controller.PlayerController;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import com.marianocarrizo.dynamicMusic.config.SessionConfig;
import com.marianocarrizo.dynamicMusic.config.SessionConfigManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
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

        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
