package com.marianocarrizo.dynamicMusic;

import com.marianocarrizo.dynamicMusic.Controller.PlayerController;
import com.marianocarrizo.dynamicMusic.Enums.TrackType;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MusicServiceStarter extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Carga la vista FXML desde la carpeta de recursos
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/player_view.fxml"));
        Parent root = loader.load();

        stage.setTitle("Dynamic Music Player");
        Scene scene = new Scene(root);

        // Conectamos el archivo CSS para darle el Look Oscuro
        try {
            scene.getStylesheets().add(getClass().getResource("/styles/dark-theme.css").toExternalForm());
        } catch (Exception e) {
            System.out.println("No se encontró el archivo CSS de estilos, se usará el diseño nativo.");
        }

        stage.setScene(scene);

        // Captura de teclado numérico para cambiar tracks sobre la marcha
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