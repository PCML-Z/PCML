package com.pmcl.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

final class FileDigest {
    private FileDigest() {}

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SHA-256 计算失败", e);
        }
    }

    /** jar 能作为 zip 打开，并且至少有一个条目。其它文件不检查。 */
    static boolean contentLooksValid(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        if (!name.endsWith(".jar")) return true;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.entries().hasMoreElements();
        } catch (IOException e) {
            return false;
        }
    }
}
