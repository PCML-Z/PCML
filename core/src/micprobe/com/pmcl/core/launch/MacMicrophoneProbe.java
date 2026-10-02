package com.pmcl.core.launch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

/** Java 8 字节码。由游戏自己的 Java 运行，向 macOS 申请该 Java 的麦克风权限。 */
public final class MacMicrophoneProbe {
    public static void main(String[] args) {
        TargetDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, 1600);
            line.start();
            Thread.sleep(200);
        } catch (Throwable ignored) {
        } finally {
            if (line != null) {
                try { line.stop(); } catch (Throwable ignored) {}
                try { line.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
