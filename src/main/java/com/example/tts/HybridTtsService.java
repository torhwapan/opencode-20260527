package com.example.tts;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HybridTtsService implements TtsService {

    private final List<TtsService> engines;

    public HybridTtsService() {
        this.engines = new ArrayList<>();

        try {
            FreeTtsService en = new FreeTtsService();
            this.engines.add(en);
            System.out.println("[HybridTTS] FreeTTS loaded: kevin16 (English)");
        } catch (Exception e) {
            System.out.println("[HybridTTS] FreeTTS unavailable: " + e.getMessage());
        }

        try {
            MbroTtsService cn = new MbroTtsService();
            if (checkMbrolaAvailable()) {
                this.engines.add(cn);
                System.out.println("[HybridTTS] MBROLA loaded: cn1/cn2 voice pack (Chinese)");
            } else {
                System.out.println("[HybridTTS] MBROLA not found. Chinese TTS skipped.");
            }
        } catch (Exception e) {
            System.out.println("[HybridTTS] MBROLA unavailable: " + e.getMessage());
        }

        if (this.engines.isEmpty()) {
            throw new IllegalStateException("No TTS engines available.");
        }
    }

    @Override
    public File textToWav(String text, String outputPath) throws Exception {
        for (TtsService engine : engines) {
            if (engine.supports(text)) {
                return engine.textToWav(text, outputPath);
            }
        }
        return engines.get(0).textToWav(text, outputPath);
    }

    @Override
    public boolean supports(String text) {
        return engines.stream().anyMatch(e -> e.supports(text));
    }

    private boolean checkMbrolaAvailable() {
        try {
            String cmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            Process p = new ProcessBuilder(cmd, "mbrola.exe").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
