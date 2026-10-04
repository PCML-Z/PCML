package com.pmcl.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoojayDiscoTest {

    private static final String MAC_ZULU =
            "https://api.foojay.io/disco/v3.0/packages?distro=zulu&architecture=aarch64"
                    + "&operating_system=macos&archive_type=tar.gz&package_type=jdk&release_status=ga"
                    + "&latest=available&javafx_bundled=false&directly_downloadable=true";

    @Test
    void vendorsMatchThePickerAndStayUnique() {
        List<FoojayDisco.Vendor> vendors = FoojayDisco.vendors();
        assertEquals(10, vendors.size());
        assertEquals("corretto", vendors.get(0).getId());
        assertEquals("zulu", vendors.get(1).getId());
        assertEquals("sap_machine", vendors.get(9).getId());
        assertEquals(10L, vendors.stream().map(FoojayDisco.Vendor::getId).distinct().count());
        assertTrue(FoojayDisco.isVendor("oracle_open_jdk"));
        assertTrue(!FoojayDisco.isVendor("zulu/../x"));
    }

    @Test
    void packageQueryFollowsTheHost() {
        assertEquals(MAC_ZULU, FoojayDisco.packagesUrl("zulu", "Mac OS X", "aarch64", "", "tar.gz"));
        assertEquals(
                "https://api.foojay.io/disco/v3.0/packages?distro=temurin&architecture=x64"
                        + "&operating_system=windows&archive_type=zip&package_type=jdk&release_status=ga"
                        + "&latest=available&javafx_bundled=false&directly_downloadable=true",
                FoojayDisco.packagesUrl("temurin", "Windows 11", "amd64", "glibc", null));
        String linux = FoojayDisco.packagesUrl("liberica", "Linux", "aarch64", "musl", null);
        assertTrue(linux.contains("operating_system=linux"));
        assertTrue(linux.contains("architecture=aarch64"));
        assertTrue(linux.contains("archive_type=tar.gz"));
        assertTrue(linux.contains("libc_type=musl"));
        assertTrue(!linux.contains("libc_type=glibc"));
        assertThrows(IllegalArgumentException.class,
                () -> FoojayDisco.packagesUrl("nope", "Mac OS X", "aarch64", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> FoojayDisco.packagesUrl("zulu", "FreeBSD", "aarch64", "", null));
        assertEquals("", FoojayDisco.detectLibc("Mac OS X"));
    }

    @Test
    void packageInfoUrlRejectsAnythingButAHexId() {
        assertEquals(
                "https://api.foojay.io/disco/v3.0/ids/a355becd9bb270bf5a0beddd127e9e36",
                FoojayDisco.packageInfoUrl("A355BECD9BB270BF5A0BEDDD127E9E36"));
        assertThrows(IllegalArgumentException.class, () -> FoojayDisco.packageInfoUrl("../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> FoojayDisco.packageInfoUrl("a355becd9bb270bf5a0beddd127e9e36/redirect"));
    }

    @Test
    void parsePackagesKeepsOneGaJdkPerMajor() throws Exception {
        List<FoojayDisco.Build> builds = FoojayDisco.parsePackages("""
                {"result":[
                  {"major_version":17,"java_version":"17.0.16","filename":"j17.tar.gz","size":10,
                   "id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","package_type":"jdk","release_status":"ga",
                   "javafx_bundled":false,"latest_build_available":true},
                  {"major_version":21,"java_version":"21.0.1","filename":"old.tar.gz","size":10,
                   "id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","package_type":"jdk","release_status":"ga",
                   "javafx_bundled":false,"latest_build_available":false},
                  {"major_version":21,"java_version":"21.0.8","filename":"new.tar.gz","size":11,
                   "id":"cccccccccccccccccccccccccccccccc","package_type":"jdk","release_status":"ga",
                   "javafx_bundled":false,"latest_build_available":true},
                  {"major_version":11,"java_version":"11","filename":"fx.tar.gz","size":10,
                   "id":"dddddddddddddddddddddddddddddddd","package_type":"jdk","release_status":"ga",
                   "javafx_bundled":true},
                  {"major_version":8,"java_version":"8","filename":"ea.tar.gz","size":10,
                   "id":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","package_type":"jdk","release_status":"ea",
                   "javafx_bundled":false},
                  {"major_version":16,"java_version":"16","filename":"fromlink.tar.gz","size":4,
                   "package_type":"jdk","release_status":"ga","javafx_bundled":false,
                   "links":{"pkg_info_uri":"https://api.foojay.io/disco/v3.0/ids/ffffffffffffffffffffffffffffffff"}}
                ]}
                """);
        assertEquals(List.of(21, 17, 16), builds.stream().map(FoojayDisco.Build::getMajor).toList());
        assertEquals("21.0.8", builds.get(0).getJavaVersion());
        assertEquals("cccccccccccccccccccccccccccccccc", builds.get(0).getId());
        assertEquals("ffffffffffffffffffffffffffffffff", builds.get(2).getId());
        assertEquals(0, FoojayDisco.parsePackages("{\"result\":[]}").size());
        assertThrows(java.io.IOException.class, () -> FoojayDisco.parsePackages("{"));
    }

    @Test
    void packageInfoRequiresSha256AndAPublicHttpsUrl() throws Exception {
        FoojayDisco.Resolved ok = FoojayDisco.parsePackageInfo("""
                {"result":[{"checksum":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "checksum_type":"sha256",
                "direct_download_uri":"https://cdn.azul.com/zulu/bin/zulu21.tar.gz",
                "filename":"zulu21.tar.gz"}]}
                """);
        assertEquals("https://cdn.azul.com/zulu/bin/zulu21.tar.gz", ok.getUrl());
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", ok.getSha256());
        assertEquals("zulu21.tar.gz", ok.getFilename());
        assertThrows(java.io.IOException.class, () -> FoojayDisco.parsePackageInfo("""
                {"result":[{"checksum":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "checksum_type":"sha1",
                "direct_download_uri":"https://cdn.azul.com/zulu/bin/zulu21.tar.gz",
                "filename":"zulu21.tar.gz"}]}
                """));
        assertNull(FoojayDisco.rejectDownloadUrl("https://cdn.azul.com/zulu/bin/zulu21.tar.gz"));
        assertNull(FoojayDisco.rejectDownloadUrl("https://172.32.0.1/a.tar.gz"));
        assertTrue(FoojayDisco.rejectDownloadUrl("http://cdn.azul.com/zulu/bin/zulu21.tar.gz") != null);
        assertTrue(FoojayDisco.rejectDownloadUrl("https://user@cdn.azul.com/zulu/bin/zulu21.tar.gz") != null);
        assertTrue(FoojayDisco.rejectDownloadUrl("https://127.0.0.1/zulu21.tar.gz") != null);
        assertTrue(FoojayDisco.rejectDownloadUrl("https://10.1.2.3/a.tar.gz") != null);
        assertTrue(FoojayDisco.rejectDownloadUrl("https://192.168.0.5/a.tar.gz") != null);
        assertTrue(FoojayDisco.rejectDownloadUrl("https://172.16.0.1/a.tar.gz") != null);
    }

    @Test
    void jdkStyleSymlinkStaysInsideAndEscapeIsRejected(@TempDir Path dir) throws Exception {
        assertTrue(JavaRuntimeDownloader.tarLinkStaysInside(
                "Contents/MacOS/libjli.dylib", "../Home/lib/libjli.dylib"));
        assertTrue(!JavaRuntimeDownloader.tarLinkStaysInside("bin/java", "/etc/passwd"));
        assertTrue(!JavaRuntimeDownloader.tarLinkStaysInside("bin/java", "../../etc/passwd"));

        Path root = dir.resolve("root");
        Files.createDirectories(root.resolve("Contents/Home/lib"));
        Files.createDirectories(root.resolve("Contents/MacOS"));
        Files.writeString(root.resolve("Contents/Home/lib/libjli.dylib"), "x");
        Files.createSymbolicLink(
                root.resolve("Contents/MacOS/libjli.dylib"),
                Path.of("../Home/lib/libjli.dylib"));
        Path ok = dir.resolve("ok.tar.gz");
        tar(ok, root, "Contents");
        JavaRuntimeDownloader.assertTarMembersSafe(ok);

        Path badRoot = dir.resolve("bad");
        Files.createDirectories(badRoot.resolve("bin"));
        Files.createSymbolicLink(badRoot.resolve("bin/java"), Path.of("../../etc/passwd"));
        Path bad = dir.resolve("bad.tar.gz");
        tar(bad, badRoot, "bin");
        assertThrows(java.io.IOException.class, () -> JavaRuntimeDownloader.assertTarMembersSafe(bad));
    }

    private static void tar(Path archive, Path root, String member) throws Exception {
        Process p = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", root.toString(), member)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertEquals(0, p.waitFor());
    }
}
