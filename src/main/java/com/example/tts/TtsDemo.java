package com.example.tts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class TtsDemo {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            System.out.println("=== TTS Demo (FreeTTS + Voice Packs) ===");
            System.out.println("1 - FreeTTS built-in voice (English)");
            System.out.println("2 - MBROLA Chinese voice pack");
            System.out.println("3 - Hybrid auto-detect\n");

            TtsService tts;
            System.out.print("Choose engine (1/2/3, default=1): ");
            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "2":
                    tts = new MbroTtsService();
                    System.out.println("Engine: MBROLA Chinese voice pack");
                    break;
                case "3":
                    tts = new HybridTtsService();
                    System.out.println("Engine: Hybrid auto-detect");
                    break;
                default:
                    tts = new FreeTtsService();
                    System.out.println("Engine: FreeTTS kevin16 (English)");
                    break;
            }

            while (true) {
                System.out.print("\nEnter text (or 'quit'): ");
                String text = scanner.nextLine();
                if ("quit".equalsIgnoreCase(text.trim())) break;

                String fileName = "tts_" + System.currentTimeMillis();
                Path outputDir = Path.of("output");
                Files.createDirectories(outputDir);
                String outputPath = outputDir.resolve(fileName).toAbsolutePath().toString();

                System.out.println("Processing...");
                long start = System.currentTimeMillis();
                File wavFile = tts.textToWav(text, outputPath);
                long elapsed = System.currentTimeMillis() - start;

                System.out.printf("Done! %s (%d KB, %dms)%n",
                        wavFile.getAbsolutePath(),
                        wavFile.length() / 1024,
                        elapsed);
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            scanner.close();
        }
    }
}
