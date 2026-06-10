package com.example.tts;

import java.io.File;

public interface TtsService {
    File textToWav(String text, String outputPath) throws Exception;
    boolean supports(String text);
}
