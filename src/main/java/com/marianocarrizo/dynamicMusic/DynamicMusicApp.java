package com.marianocarrizo.dynamicMusic;

import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DynamicMusicApp {

    public static void main(String[] args) {
        Application.launch(MusicServiceStarter.class, args);
    }

}
