import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Release CI helper. Signs the canonical PMCL update payload for one asset.
 *
 * Usage: java tools/SignUpdateAsset.java <version> <download-url> <asset-path>
 * Env: PMCL_UPDATE_ED25519_PRIVATE_KEY = Base64 PKCS#8 Ed25519 private key.
 */
public final class SignUpdateAsset {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SignUpdateAsset <version> <download-url> <asset-path>");
        }
        String privateKeyB64 = System.getenv("PMCL_UPDATE_ED25519_PRIVATE_KEY");
        if (privateKeyB64 == null || privateKeyB64.isBlank()) {
            throw new IllegalStateException(
                    "Missing PMCL_UPDATE_ED25519_PRIVATE_KEY GitHub secret");
        }

        String version = args[0];
        String url = args[1];
        Path asset = Path.of(args[2]).toAbsolutePath().normalize();
        long size = Files.size(asset);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(asset)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        String payload = "PMCL-UPDATE-V1\n"
                + version + '\n'
                + url + '\n'
                + sha256 + '\n'
                + '\n'
                + size + '\n';

        byte[] keyBytes = Base64.getDecoder().decode(
                privateKeyB64.replaceAll("\\s+", ""));
        PrivateKey key = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        Files.writeString(Path.of(asset + ".sig"), signature + "\n", StandardCharsets.UTF_8);
        System.out.println("Signed " + asset.getFileName() + " sha256=" + sha256);
    }
}
