module com.marianocarrizo.dynamicMusic {
    requires java.net.http;

    // Dependencias de JavaFX
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;

    // javax.sound.sampled (usado en ConversorUtils para conversión FLAC y cálculo RMS)
    requires java.desktop;

    // TarsosDSP ya es modular (incluye module-info.class en sus JARs 2.5)
    requires TarsosDSP.core;
    requires TarsosDSP.jvm;

    // jflac-codec: módulo automático en compilación; moditect le agrega module-info en package
    requires jflac.codec;

    // Gson: serialización JSON para persistencia de sesión (G3)
    requires com.google.gson;

    // Punto de entrada de la aplicación (Application.launch lo necesita)
    exports com.marianocarrizo.dynamicMusic;

    // Reflective access para JavaFX Application en el arranque
    opens com.marianocarrizo.dynamicMusic to javafx.graphics;

    // Reflective access para inyección FXML (@FXML) en el controlador
    opens com.marianocarrizo.dynamicMusic.Controller to javafx.fxml;

    // Reflective access para Gson sobre el POJO de configuración de sesión
    opens com.marianocarrizo.dynamicMusic.config to com.google.gson;

    // Reflective access para Gson sobre las POJOs del modelo (Playlist, etc.)
    opens com.marianocarrizo.dynamicMusic.model to com.google.gson;
}
