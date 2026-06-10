package com.example.tts;

import com.sun.speech.freetts.Voice;
import com.sun.speech.freetts.VoiceManager;
import com.sun.speech.freetts.audio.SingleFileAudioPlayer;

import javax.sound.sampled.AudioFileFormat;
import java.io.File;
import java.util.Arrays;

public class FreeTtsService implements TtsService {

    public enum VoicePack {
        KEVIN16("kevin16", "American English male (16kHz)"),
        KEVIN("kevin", "American English male (8kHz)"),
        ALAN("alan", "American English male (limited)"),
        TOM("tom", "American English male (limited)");

        final String name;
        final String desc;
        VoicePack(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }
    }

    private final Voice voice;

    public FreeTtsService() {
        this(VoicePack.KEVIN16);
    }

    public FreeTtsService(VoicePack pack) {
        this(pack.name);
    }

    public FreeTtsService(String voiceName) {
        System.setProperty("freetts.voices",
                "com.sun.speech.freetts.en.us.cmu_us_kal.KevinVoiceDirectory" +
                " com.sun.speech.freetts.en.us.cmu_time_awb.AlanVoiceDirectory" +
                " com.sun.speech.freetts.en.us.cmu_us_awb.AwbVoiceDirectory");

        this.voice = VoiceManager.getInstance().getVoice(voiceName);
        if (this.voice == null) {
            throw new IllegalStateException("Voice pack [" + voiceName + "] not found. " +
                    "Available: " + Arrays.toString(VoicePack.values()));
        }
        this.voice.setRate(150);
        this.voice.setPitch(100);
        this.voice.setVolume(10);
    }

    @Override
    public File textToWav(String text, String outputPath) throws Exception {
        String wavPath = outputPath.endsWith(".wav") ? outputPath : outputPath + ".wav";
        String baseName = wavPath.replaceAll("\\.wav$", "");

        voice.allocate();
        SingleFileAudioPlayer audioPlayer = new SingleFileAudioPlayer(baseName, AudioFileFormat.Type.WAVE);
        voice.setAudioPlayer(audioPlayer);
        voice.speak(text);
        audioPlayer.close();
        voice.deallocate();

        return new File(wavPath).exists() ? new File(wavPath) : new File(baseName + ".wav");
    }

    @Override
    public boolean supports(String text) {
        if (text == null || text.isBlank()) return false;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }
}
