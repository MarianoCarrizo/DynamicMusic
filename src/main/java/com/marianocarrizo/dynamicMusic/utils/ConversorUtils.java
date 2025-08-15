package com.marianocarrizo.dynamicMusic.utils;

import lombok.experimental.UtilityClass;

import javax.sound.sampled.*;

import java.io.File;
import java.io.IOException;

@UtilityClass
public class ConversorUtils {

    private static final double TARGET_RMS = 0.1; // Nivel de referencia

    public static File convertFlacToWav(File flacFile) throws Exception {
        File wavFile = File.createTempFile("track_", ".wav");
        wavFile.deleteOnExit(); // se borra automáticamente al cerrar la JVM

        AudioInputStream flacStream = AudioSystem.getAudioInputStream(flacFile);
        AudioFormat baseFormat = flacStream.getFormat();
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(),
                false
        );

        AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, flacStream);
        AudioSystem.write(pcmStream, AudioFileFormat.Type.WAVE, wavFile);

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
