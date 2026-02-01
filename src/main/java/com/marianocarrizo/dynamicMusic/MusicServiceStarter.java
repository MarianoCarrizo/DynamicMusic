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
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/views/player_view.fxml"));
        Parent root = loader.load();
        stage.setTitle("Dynamic Music Player");

        Scene scene = new Scene(root);
        stage.setScene(scene);

        PlayerController controller = loader.getController();
        scene.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DIGIT1 -> controller.switchTrack(TrackType.PAUSED);
                case DIGIT2 -> controller.switchTrack(TrackType.RAGED);
                case DIGIT3 -> controller.switchTrack(TrackType.NORMAL);
                case DIGIT4 -> controller.switchTrack(TrackType.ADVANTAGE);
            }
        });

        stage.show();
    }
}
