package com.example.tts;

import java.io.*;
import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * FreeTTS + MBROLA 外部语音包方案，用于中文TTS。
 *
 * 工作流程：
 *   Java代码 -> 中文文本 -> Pinyin转换 -> MBROLA音素映射 -> mbrola.exe -> WAV
 *
 * ===== 环境准备 =====
 * 1. 下载 MBROLA 二进制: https://github.com/numediart/MBROLA
 *    Windows 用 mbrola.exe，放到系统 PATH 或通过 setMbrolaPath() 配置
 *
 * 2. 下载中文 MBROLA 语音包:
 *    cn1 (男声): https://github.com/numediart/MBROLA-voices/tree/master/data/cn1
 *    cn2 (女声): https://github.com/numediart/MBROLA-voices/tree/master/data/cn2
 *    下载 .dat 文件放到 voices/ 目录
 *
 * 3. 确保 Java 环境有 pinyin4j 或自行实现汉字 -> 拼音 mapping
 *    当前采用 pinyin4j 方案:
 *    Maven: com.belerweb:pinyin4j:2.5.1
 */
public class MbroTtsService implements TtsService {

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final long TIMEOUT_SECONDS = 30;

    private String mbrolaPath = "mbrola.exe";
    private String voiceDbDir = "voices";

    public MbroTtsService() {
    }

    public MbroTtsService(String mbrolaPath, String voiceDbDir) {
        this.mbrolaPath = mbrolaPath;
        this.voiceDbDir = voiceDbDir;
    }

    @Override
    public File textToWav(String text, String outputPath) throws Exception {
        String wavPath = outputPath.endsWith(".wav") ? outputPath : outputPath + ".wav";
        String voiceDb = voiceDbDir + File.separator + "cn1";

        if (!new File(voiceDb).exists()) {
            voiceDb = voiceDbDir + File.separator + "cn2";
        }

        if (!new File(mbrolaPath).exists() && !isInPath(mbrolaPath)) {
            throw new FileNotFoundException("MBROLA binary not found: " + mbrolaPath +
                    ". Download from https://github.com/numediart/MBROLA");
        }
        if (!new File(voiceDb).exists()) {
            throw new FileNotFoundException("Chinese voice pack not found: " + voiceDb +
                    ". Download from https://github.com/numediart/MBROLA-voices");
        }

        File phoFile = File.createTempFile("tts_", ".pho");
        phoFile.deleteOnExit();

        try {
            generatePhoFile(text, phoFile);

            ProcessBuilder pb = new ProcessBuilder(
                    mbrolaPath, "-e", voiceDb,
                    phoFile.getAbsolutePath(), wavPath
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("MBROLA synthesis timed out");
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getInputStream().readAllBytes());
                throw new RuntimeException("MBROLA failed (exit=" + process.exitValue() + "): " + err);
            }

            File wavFile = new File(wavPath);
            if (!wavFile.exists()) {
                throw new RuntimeException("Output WAV not found: " + wavPath);
            }
            return wavFile;

        } finally {
            phoFile.delete();
            cleanTempFiles(wavPath.replaceAll("\\.wav$", ".wav"));
        }
    }

    @Override
    public boolean supports(String text) {
        return text != null && CHINESE_PATTERN.matcher(text).find();
    }

    private void generatePhoFile(String chineseText, File outputPho) throws Exception {
        StringBuilder pho = new StringBuilder();

        for (char c : chineseText.toCharArray()) {
            if (c == ' ') {
                pho.append("\n");
                continue;
            }
            if (Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN) {
                pho.append(asciiToMbroPhoneme(c)).append(" _ 100\n");
                continue;
            }

            // 汉字 -> 拼音 -> MBROLA音素
            String pinyin = charToPinyin(c);
            if (pinyin == null || pinyin.isBlank()) continue;

            String[] mbroPhones = pinyinToMbroPhonemes(pinyin);
            for (String ph : mbroPhones) {
                pho.append(ph).append(" _ 100\n");
            }
        }

        Files.writeString(outputPho.toPath(), pho.toString());
    }

    private String charToPinyin(char ch) {
        try {
            Class<?> cls = Class.forName("net.sourceforge.pinyin4j.PinyinHelper");
            Object[] pinyins = (Object[]) cls.getMethod("toHanyuPinyinStringArray", char.class).invoke(null, ch);
            if (pinyins != null && pinyins.length > 0) {
                String raw = pinyins[0].toString();
                return raw.substring(0, raw.length() - 1);
            }
        } catch (ClassNotFoundException e) {
            // pinyin4j不在classpath，用内置简易映射
        } catch (Exception ignored) {
        }
        return simpleCharToPinyin(ch);
    }

    private String[] pinyinToMbroPhonemes(String pinyin) {
        String tone = "";
        if (pinyin.matches(".*[1-5]$")) {
            tone = pinyin.substring(pinyin.length() - 1);
            pinyin = pinyin.substring(0, pinyin.length() - 1);
        }
        String[] phones = PINYIN_MBRO_MAP.getOrDefault(pinyin,
                new String[]{pinyin, "_"});
        if (!tone.isEmpty()) {
            for (int i = 0; i < phones.length; i++) {
                phones[i] = phones[i] + tone;
            }
        }
        return phones;
    }

    private String asciiToMbroPhoneme(char c) {
        return "_";
    }

    private void cleanTempFiles(String basePath) {
    }

    private boolean isInPath(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which",
                    cmd);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String simpleCharToPinyin(char ch) {
        String s = String.valueOf(ch);
        for (int i = 0; i < SIMPLE_PINYIN.length; i++) {
            if (SIMPLE_PINYIN[i][0].contains(s)) {
                return SIMPLE_PINYIN[i][1];
            }
        }
        return null;
    }

    private static final String[][] SIMPLE_PINYIN = {
        {"你我他她它", "wo"}, {"你好", "ni"}, {"好", "hao"},
        {"的大", "da"}, {"小", "xiao"}, {"人", "ren"},
        {"中是", "zhong"}, {"国", "guo"}, {"文", "wen"},
        {"字", "zi"}, {"说", "shuo"}, {"话", "hua"},
        {"测", "ce"}, {"试", "shi"}, {"功", "gong"},
        {"能", "neng"}, {"一", "yi"}, {"是", "shi"},
        {"不", "bu"}, {"了", "le"}, {"有", "you"},
        {"这", "zhe"}, {"那", "na"}, {"什", "shen"},
        {"么", "me"}, {"我", "wo"}, {"在", "zai"},
        {"和", "he"}, {"就", "jiu"}, {"也", "ye"},
        {"对", "dui"}, {"上", "shang"}, {"下", "xia"},
        {"看", "kan"}, {"听", "ting"}, {"吃", "chi"},
        {"喝", "he"}, {"走", "zou"}, {"来", "lai"},
        {"去", "qu"}, {"天", "tian"}, {"地", "di"},
        {"日", "ri"}, {"月", "yue"}, {"水", "shui"},
        {"火", "huo"}, {"风", "feng"}, {"云", "yun"},
        {"山", "shan"}, {"石", "shi"}, {"用", "yong"},
        {"知", "zhi"}, {"道", "dao"}, {"生", "sheng"},
        {"知", "zhi"}, {"道", "dao"}, {"系", "xi"},
        {"统", "tong"}, {"信", "xin"}, {"息", "xi"},
    };

    private static final java.util.Map<String, String[]> PINYIN_MBRO_MAP = new java.util.HashMap<>();
    static {
        PINYIN_MBRO_MAP.put("wo", new String[]{"w", "o"});
        PINYIN_MBRO_MAP.put("ni", new String[]{"n", "i"});
        PINYIN_MBRO_MAP.put("hao", new String[]{"h", "a", "o"});
        PINYIN_MBRO_MAP.put("da", new String[]{"d", "a"});
        PINYIN_MBRO_MAP.put("xiao", new String[]{"x", "i", "a", "o"});
        PINYIN_MBRO_MAP.put("ren", new String[]{"r", "e", "n"});
        PINYIN_MBRO_MAP.put("zhong", new String[]{"zh", "o", "ng"});
        PINYIN_MBRO_MAP.put("guo", new String[]{"g", "u", "o"});
        PINYIN_MBRO_MAP.put("wen", new String[]{"w", "e", "n"});
        PINYIN_MBRO_MAP.put("zi", new String[]{"z", "i"});
        PINYIN_MBRO_MAP.put("shuo", new String[]{"sh", "u", "o"});
        PINYIN_MBRO_MAP.put("hua", new String[]{"h", "u", "a"});
        PINYIN_MBRO_MAP.put("ce", new String[]{"c", "e"});
        PINYIN_MBRO_MAP.put("shi", new String[]{"sh", "i"});
        PINYIN_MBRO_MAP.put("gong", new String[]{"g", "o", "ng"});
        PINYIN_MBRO_MAP.put("neng", new String[]{"n", "e", "ng"});
        PINYIN_MBRO_MAP.put("yi", new String[]{"y", "i"});
        PINYIN_MBRO_MAP.put("bu", new String[]{"b", "u"});
        PINYIN_MBRO_MAP.put("le", new String[]{"l", "e"});
        PINYIN_MBRO_MAP.put("you", new String[]{"y", "o", "u"});
        PINYIN_MBRO_MAP.put("zhe", new String[]{"zh", "e"});
        PINYIN_MBRO_MAP.put("na", new String[]{"n", "a"});
        PINYIN_MBRO_MAP.put("shen", new String[]{"sh", "e", "n"});
        PINYIN_MBRO_MAP.put("me", new String[]{"m", "e"});
        PINYIN_MBRO_MAP.put("zai", new String[]{"z", "a", "i"});
        PINYIN_MBRO_MAP.put("he", new String[]{"h", "e"});
        PINYIN_MBRO_MAP.put("jiu", new String[]{"j", "i", "o", "u"});
        PINYIN_MBRO_MAP.put("ye", new String[]{"y", "e"});
        PINYIN_MBRO_MAP.put("dui", new String[]{"d", "u", "i"});
        PINYIN_MBRO_MAP.put("shang", new String[]{"sh", "a", "ng"});
        PINYIN_MBRO_MAP.put("kan", new String[]{"k", "a", "n"});
        PINYIN_MBRO_MAP.put("ting", new String[]{"t", "i", "ng"});
        PINYIN_MBRO_MAP.put("chi", new String[]{"ch", "i"});
        PINYIN_MBRO_MAP.put("zou", new String[]{"z", "o", "u"});
        PINYIN_MBRO_MAP.put("lai", new String[]{"l", "a", "i"});
        PINYIN_MBRO_MAP.put("qu", new String[]{"q", "u"});
        PINYIN_MBRO_MAP.put("tian", new String[]{"t", "i", "a", "n"});
        PINYIN_MBRO_MAP.put("di", new String[]{"d", "i"});
        PINYIN_MBRO_MAP.put("ri", new String[]{"r", "i"});
        PINYIN_MBRO_MAP.put("yue", new String[]{"y", "u", "e"});
        PINYIN_MBRO_MAP.put("shui", new String[]{"sh", "u", "i"});
        PINYIN_MBRO_MAP.put("huo", new String[]{"h", "u", "o"});
        PINYIN_MBRO_MAP.put("feng", new String[]{"f", "e", "ng"});
        PINYIN_MBRO_MAP.put("yun", new String[]{"y", "u", "n"});
        PINYIN_MBRO_MAP.put("shan", new String[]{"sh", "a", "n"});
        PINYIN_MBRO_MAP.put("yong", new String[]{"y", "o", "ng"});
        PINYIN_MBRO_MAP.put("zhi", new String[]{"zh", "i"});
        PINYIN_MBRO_MAP.put("sheng", new String[]{"sh", "e", "ng"});
        PINYIN_MBRO_MAP.put("xi", new String[]{"x", "i"});
        PINYIN_MBRO_MAP.put("tong", new String[]{"t", "o", "ng"});
        PINYIN_MBRO_MAP.put("xin", new String[]{"x", "i", "n"});
    }
}
