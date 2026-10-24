package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.model.AuraPreset;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultBackgroundsTest {

    @Test
    void everyPresetMapsToAnExistingResource() throws Exception {
        for (AuraPreset p : AuraPreset.values()) {
            String path = DefaultBackgrounds.resourcePath(p);
            try (InputStream in = DefaultBackgrounds.class.getResourceAsStream(path)) {
                assertNotNull(in, p + " -> " + path + " is missing from the classpath");
                // PNG signature
                assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, in.readNBytes(4), path);
            }
        }
    }

    @Test
    void presetsMapToDistinctResources() {
        Set<String> paths = new HashSet<>();
        for (AuraPreset p : AuraPreset.values()) {
            assertTrue(paths.add(DefaultBackgrounds.resourcePath(p)), "duplicate resource for " + p);
        }
    }

    @Test
    void nullPresetFallsBackToNeutral() {
        assertEquals(DefaultBackgrounds.resourcePath(AuraPreset.NEUTRAL), DefaultBackgrounds.resourcePath(null));
    }
}
