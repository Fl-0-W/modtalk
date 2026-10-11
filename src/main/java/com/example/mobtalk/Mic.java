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

    /** Меняет высоту (и немного темп) голоса: factor > 1 — выше, < 1 — ниже. 16 бит, моно. */
    public static byte[] resample(byte[] in, double factor) {
        int n = in.length / 2;
        if (n < 2 || factor <= 0) return in;
        int m = (int) (n / factor);
        byte[] out = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            double pos = i * factor;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, n - 1);
            double fr = pos - i0;
            short a = (short) ((in[2 * i0] & 0xFF) | (in[2 * i0 + 1] << 8));
            short b = (short) ((in[2 * i1] & 0xFF) | (in[2 * i1 + 1] << 8));
            short v = (short) Math.round(a + (b - a) * fr);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /**
     * Воспроизведение с учётом расстояния до говорящего: громкость и «чистота» голоса пересчитываются каждые 20 мс
     * по текущему расстоянию, поэтому если отойти, голос затихает и глохнет прямо во время фразы.
     */
    public static void playSpatial(byte[] pcm, MobTalkClient.Speaker sp) {
        Config c = Config.INSTANCE;
        double full = Math.max(0.5, c.fullVolumeRadius);
        double hear = Math.max(full + 1.0, c.hearRadius);
        final int chunk = 960; // 480 отсчётов = 20 мс при 24 кГц
        try (SourceDataLine sdl = AudioSystem.getSourceDataLine(PLAY)) {
            sdl.open(PLAY);
            sdl.start();
            double prevGain = -1, lp = 0;
            for (int off = 0; off + 1 < pcm.length; off += chunk) {
                int len = Math.min(chunk, pcm.length - off);
                len -= len % 2;
                if (len <= 0) break;
                double t = Math.max(0.0, Math.min(1.0, (sp.dist - full) / (hear - full)));
                double gain = (1.0 - t) * (1.0 - t);
                double alpha = c.distanceMuffle ? 1.0 - 0.82 * Math.pow(t, 0.7) : 1.0;
                if (prevGain < 0) prevGain = gain;
                int n = len / 2;
                byte[] out = new byte[len];
                for (int i = 0; i < n; i++) {
                    double x = (short) ((pcm[off + 2 * i] & 0xFF) | (pcm[off + 2 * i + 1] << 8));
                    lp += alpha * (x - lp);
                    double g = prevGain + (gain - prevGain) * (i + 1) / n;
                    int v = (int) Math.round(lp * g);
                    v = Math.max(-32768, Math.min(32767, v));
                    out[2 * i] = (byte) v;
                    out[2 * i + 1] = (byte) (v >> 8);
                }
                prevGain = gain;
                sdl.write(out, 0, len);
            }
            sdl.drain();
        } catch (Exception e) {
            MobTalkClient.LOG.error("Playback error", e);
        }
    }

    public static void play(byte[] pcm, float gain) {
        if (gain < 0.99f) {
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                short v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                v = (short) (v * gain);
                pcm[i] = (byte) v;
                pcm[i + 1] = (byte) (v >> 8);
            }
        }
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
