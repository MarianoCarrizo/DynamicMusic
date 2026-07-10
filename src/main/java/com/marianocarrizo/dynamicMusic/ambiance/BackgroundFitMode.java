package com.marianocarrizo.dynamicMusic.ambiance;

/**
 * Modos de ajuste del fondo visual en el stage de ambiance.
 * Controla cómo se escala y recorta la imagen o video de fondo.
 */
public enum BackgroundFitMode {

    /** Cubre el área completa con recorte centrado; preserva aspecto. */
    FILL,

    /** Muestra el fondo completo con barras en los bordes cortos; preserva aspecto. */
    FIT,

    /** Estira el fondo para cubrir todo el área; puede distorsionar el aspecto. */
    STRETCH,

    /** Muestra el fondo en tamaño natural centrado; el desborde queda recortado. */
    CENTER;

    /**
     * Convierte un String al enum correspondiente.
     * Retorna {@code FILL} ante null o valor desconocido, sin lanzar excepciones.
     *
     * @param s nombre del modo (e.g. "FILL", "FIT")
     * @return el modo correspondiente, o FILL como fallback seguro
     */
    public static BackgroundFitMode fromString(String s) {
        if (s == null) return FILL;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return FILL;
        }
    }
}
