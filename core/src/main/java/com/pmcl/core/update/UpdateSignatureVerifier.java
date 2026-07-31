package com.pmcl.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * Ed25519 verification for PMCL self-update manifests.
 * <p>
 * Public key is loaded out-of-band (classpath resource, overridable via
 * {@code -Dpmcl.update.ed25519.pubkey=&lt;Base64-SPKI&gt;}). Private key never ships
 * in the client; release pipelines must sign with the matching key.
 * <p>
 * Canonical signed payload (UTF-8):
 * <pre>
 * PMCL-UPDATE-V1
 * {version}
 * {url}
 * {sha256}
 * {sha1}
 * {size}
 * </pre>
 * (trailing newline after size; empty sha fields allowed as empty lines)
 */
final class UpdateSignatureVerifier {

    static final String PAYLOAD_PREFIX = "PMCL-UPDATE-V1";
    private static final String RESOURCE = "com/pmcl/core/update/ed25519-spki.b64";
    private static final String PROP = "pmcl.update.ed25519.pubkey";

    private static final PublicKey PUBLIC_KEY = loadPublicKey();

    private UpdateSignatureVerifier() {}

    static String canonicalPayload(String version, String url, String sha256, String sha1, long size) {
        return PAYLOAD_PREFIX + '\n'
                + nullToEmpty(version) + '\n'
                + nullToEmpty(url) + '\n'
                + nullToEmpty(sha256) + '\n'
                + nullToEmpty(sha1) + '\n'
                + size + '\n';
    }

    /**
     * @param signatureB64 Base64 (standard) Ed25519 signature over {@link #canonicalPayload}
     */
    static void verifyOrThrow(String version, String url, String sha256, String sha1, long size,
                              String signatureB64) throws IOException {
        if (PUBLIC_KEY == null) {
            throw new IOException("更新验签公钥未配置，拒绝自定义清单更新");
        }
        if (signatureB64 == null || signatureB64.isBlank()) {
            throw new IOException("更新清单缺少 signature 字段，拒绝未签名更新");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signatureB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("更新签名 Base64 无效", e);
        }
        byte[] payload = canonicalPayload(version, url, sha256, sha1, size)
                .getBytes(StandardCharsets.UTF_8);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(PUBLIC_KEY);
            s.update(payload);
            if (!s.verify(sig)) {
                throw new IOException("更新清单 Ed25519 签名校验失败");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("更新签名校验异常: " + e.getMessage(), e);
        }
    }

    private static PublicKey loadPublicKey() {
        String b64 = System.getProperty(PROP, "").trim();
        if (b64.isEmpty()) {
            try (InputStream in = UpdateSignatureVerifier.class.getClassLoader()
                    .getResourceAsStream(RESOURCE)) {
                if (in == null) return null;
                b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("\\s+", "");
            } catch (IOException e) {
                return null;
            }
        }
        if (b64.isEmpty()) return null;
        try {
            byte[] der = Base64.getDecoder().decode(b64);
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            System.err.println("[SelfUpdater] 无法加载更新验签公钥: " + e.getMessage());
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return Objects.requireNonNullElse(s, "");
    }
}
