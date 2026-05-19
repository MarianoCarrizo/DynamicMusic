package com.marianocarrizo.dynamicMusic;

import com.marianocarrizo.dynamicMusic.Controller.PlayerController;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MusicServiceStarter extends Application {

    private double xOffset = 0;
    private double yOffset = 0;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/player_view.fxml"));
        Parent root = loader.load();

        stage.setTitle("Dynamic Music Player");

        // 1. Estilo sin bordes nativos
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

        // 🔥 SOLUCIÓN AL ARRASTRE: Forzamos la aplicación de estilos antes de buscar el nodo
        root.applyCss();
        root.layout();

        Node header = root.lookup(".window-header");

        if (header != null) {
            // Si encuentra la barra superior (que es lo ideal), solo arrastra desde ahí
            header.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            header.setOnMouseDragged(event -> {
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            });
        } else {
            // 🔥 PLAN B SEGURO: Si no encuentra el header, arrastramos desde el contenedor interno real (.main-container)
            // NUNCA desde el 'root' de la Scene para no romper la transparencia nativa de Windows.
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
        scene.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DIGIT1 -> controller.switchTrack(TrackType.PAUSED);
                case DIGIT2 -> controller.switchTrack(TrackType.NORMAL);
                case DIGIT3 -> controller.switchTrack(TrackType.RAGED);
                case DIGIT4 -> controller.switchTrack(TrackType.ADVANTAGE);
            }
        });

        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}