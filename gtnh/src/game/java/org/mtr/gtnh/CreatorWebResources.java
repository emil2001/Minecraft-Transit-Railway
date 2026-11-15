package org.mtr.gtnh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Serves the unchanged published MTR 4.0.5 Creator assets; see their provenance.json. */
public final class CreatorWebResources {
    private static final String RESOURCE_ROOT = "/META-INF/mtr/web/creator/";

    private CreatorWebResources() {}

    public static String get(String resource) {
        String key = resource.startsWith("/") ? resource.substring(1) : resource;
        switch (key) {
            case "chunk-OO7PIGQZ.js":
            case "chunk-VO2FIUOU.js":
            case "index.html":
            case "main-FVTXCXTH.js":
            case "polyfills-FFHMD2TL.js":
            case "styles-MKRTXLGB.css":
                break;
            default:
                return null;
        }

        try (InputStream input = CreatorWebResources.class.getResourceAsStream(RESOURCE_ROOT + key)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled MTR 4.0.5 Creator resource: " + key);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read bundled MTR 4.0.5 Creator resource: " + key, error);
        }
    }
}
