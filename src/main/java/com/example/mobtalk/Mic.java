package com.example.mobtalk;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Запись с микрофона (16 кГц моно) и воспроизведение ответа (PCM 24 кГц моно). */
public class Mic {
    private static final AudioFormat REC = new AudioFormat(16000f, 16, 1, true, false);
    private static final AudioFormat PLAY = new AudioFormat(24000f, 16, 1, true, false);

    private static TargetDataLine line;
    private static ByteArrayOutputStream buf;
    private static Thread thread;
    private static volatile boolean running;

    public static synchronized boolean start() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, REC);
            if (!AudioSystem.isLineSupported(info)) return false;
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(REC);
            line.start();
            buf = new ByteArrayOutputStream();
            running = true;
            final TargetDataLine l = line;
            final ByteArrayOutputStream b = buf;
            thread = new Thread(() -> {
                byte[] chunk = new byte[2048];
                try {
                    while (running) {
                        int n = l.read(chunk, 0, chunk.length);
                        if (n > 0) {
                            synchronized (b) {
                                b.write(chunk, 0, n);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "MobTalk-Mic");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Exception e) {
            MobTalkClient.LOG.error("Mic start error", e);
            return false;
        }
    }

    /** Останавливает запись и возвращает WAV, либо null, если запись слишком короткая. */
    public static synchronized byte[] stopWav() {
        if (line == null) return null;
        running = false;
        try {
            thread.join(400);
        } catch (InterruptedException ignored) {
        }
        try {
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
        line = null;
        byte[] pcm;
        synchronized (buf) {
            pcm = buf.toByteArray();
        }
        if (pcm.length < 16000) return null; // меньше 0.5 сек
        try {
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), REC, pcm.length / 2);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (Exception e) {
            MobTalkClient.LOG.error("WAV error", e);
            return null;
        }
    }

    public static void play(byte[] pcm) {
        try (SourceDataLine sdl = AudioSystem.getSourceDataLine(PLAY)) {
            sdl.open(PLAY);
            sdl.start();
            sdl.write(pcm, 0, pcm.length - (pcm.length % 2));
            sdl.drain();
        } catch (Exception e) {
            MobTalkClient.LOG.error("Playback error", e);
        }
    }
}
