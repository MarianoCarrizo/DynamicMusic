package com.marianocarrizo.dynamicMusic.utils;

import javax.sound.sampled.*;

import java.io.File;
import java.io.IOException;

/**
 * Utilidades de conversión y normalización de audio.
 * Clase de utilidad estática: no instanciable.
 * (Anteriormente usaba @UtilityClass de Lombok, reemplazado por constructor privado
 * explícito por incompatibilidad de Lombok con JDK 24 — TypeTag.UNKNOWN eliminado.)
 */
public final class ConversorUtils {

    private ConversorUtils() {
        // Clase de utilidad: no instanciable
    }

    private static final double TARGET_RMS = 0.1; // Nivel de referencia

    public static File convertFlacToWav(File flacFile) throws Exception {
        File wavFile = File.createTempFile("track_", ".wav");
        wavFile.deleteOnExit();

        AudioInputStream flacStream = AudioSystem.getAudioInputStream(flacFile);
        AudioFormat baseFormat = flacStream.getFormat();

        // Si el archivo origen es de 24 bits, forzamos primero la lectura a PCM_SIGNED
        // manteniendo los bits originales para que el SPI de jFLAC no falle por el tamaño del frame.
        AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                baseFormat.getSampleSizeInBits() == -1 ? 16 : baseFormat.getSampleSizeInBits(),
                baseFormat.getChannels(),
                baseFormat.getChannels() * (baseFormat.getSampleSizeInBits() == -1 ? 2 : baseFormat.getSampleSizeInBits() / 8),
                baseFormat.getSampleRate(),
                false // Little-endian
        );

        AudioInputStream pcmStream = AudioSystem.getAudioInputStream(decodedFormat, flacStream);

        // Si el formato decodificado sigue siendo de 24 bits, lo bajamos a 16 bits
        // para que JavaFX Media lo reproduzca sin problemas.
        if (decodedFormat.getSampleSizeInBits() == 24) {
            AudioFormat targetFormat16Bit = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
            );
            pcmStream = AudioSystem.getAudioInputStream(targetFormat16Bit, pcmStream);
        }

        // Guardamos el flujo final convertido en el archivo temporal .wav
        AudioSystem.write(pcmStream, AudioFileFormat.Type.WAVE, wavFile);

        pcmStream.close();
        flacStream.close();

        return wavFile;
    }

    public static double calculateGainForRMS(File audioFile) throws IOException, UnsupportedAudioFileException {
        AudioInputStream ais = AudioSystem.getAudioInputStream(audioFile);
        byte[] buffer = new byte[1024];
        int bytesRead;
        double sum = 0;
        long sampleCount = 0;

        while ((bytesRead = ais.read(buffer)) != -1) {
            for (int i = 0; i < bytesRead; i += 2) { // PCM 16 bits
                int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xff);
                double normalized = sample / 32768.0;
                sum += normalized * normalized;
                sampleCount++;
            }
        }

        ais.close();

        double rms = Math.sqrt(sum / sampleCount);
        return Math.min(TARGET_RMS / rms, 1.0); // limitamos a 1.0 para no saturar
    }
}
