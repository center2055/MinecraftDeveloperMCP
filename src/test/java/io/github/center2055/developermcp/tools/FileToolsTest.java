package io.github.center2055.developermcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsTest {

    @TempDir
    Path root;
    private FileTools tools;

    @BeforeEach
    void setUp() {
        tools = new FileTools(new PathSandbox(root), () -> 1024);
    }

    private static JsonObject args(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    @Test
    void blocksPathsOutsideServerFolder() {
        for (String bad : List.of("../secret.txt", "plugins/../../x", root.getParent().resolve("x").toString())) {
            ToolException e = assertThrows(ToolException.class, () -> tools.readFile(args("path", bad)), bad);
            assertTrue(e.getMessage().startsWith("Access denied"), e.getMessage());
        }
        assertThrows(ToolException.class, () -> tools.writeFile(args("path", "../evil.yml", "content", "x")));
    }

    @Test
    void writesReadsAndAppends() throws Exception {
        tools.writeFile(args("path", "plugins/Test/config.yml", "content", "a: 1\n"));
        JsonObject append = args("path", "plugins/Test/config.yml", "content", "b: 2\n");
        append.addProperty("append", true);
        tools.writeFile(append);
        assertEquals("a: 1\nb: 2\n", tools.readFile(args("path", "plugins/Test/config.yml")).text());
    }

    @Test
    void readsLineRanges() throws Exception {
        Files.writeString(root.resolve("big.log"), "l1\nl2\nl3\nl4\nl5\n");
        JsonObject a = args("path", "big.log");
        a.addProperty("startLine", 2);
        a.addProperty("lineCount", 2);
        String text = tools.readFile(a).text();
        assertTrue(text.startsWith("l2\nl3\n"), text);
        assertTrue(text.contains("[lines 2-3 of 6]"), text);
    }

    @Test
    void refusesFilesOverTheLimitButAllowsRanges() throws Exception {
        Files.writeString(root.resolve("huge.log"), "x".repeat(2000) + "\nend\n");
        ToolException e = assertThrows(ToolException.class, () -> tools.readFile(args("path", "huge.log")));
        assertTrue(e.getMessage().contains("get_logs"), e.getMessage());
        JsonObject a = args("path", "huge.log");
        a.addProperty("startLine", 2);
        assertTrue(tools.readFile(a).text().startsWith("end"));
    }

    @Test
    void detectsBinaryAndRoundTripsBase64() throws Exception {
        byte[] data = {0x50, 0x4B, 0x03, 0x04, 0, 1, 2, (byte) 0xFF};
        tools.writeFileBase64(args("path", "plugins/Thing.jar", "content", Base64.getEncoder().encodeToString(data)));
        assertArrayEquals(data, Files.readAllBytes(root.resolve("plugins/Thing.jar")));
        assertThrows(ToolException.class, () -> tools.readFile(args("path", "plugins/Thing.jar")));
        assertEquals(Base64.getEncoder().encodeToString(data), tools.readFileBase64(args("path", "plugins/Thing.jar")).text());
        assertThrows(ToolException.class, () -> tools.writeFileBase64(args("path", "x.bin", "content", "***")));
    }

    @Test
    void readsInvalidUtf8Leniently() throws Exception {
        Files.write(root.resolve("latin1.txt"), "café".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(tools.readFile(args("path", "latin1.txt")).text().startsWith("caf"));
    }

    @Test
    void listsDirectoriesFirst() throws Exception {
        Files.createDirectories(root.resolve("plugins/Essentials"));
        Files.writeString(root.resolve("plugins/a.jar"), "x");
        String listing = tools.listDirectory(args("path", "plugins")).text();
        assertTrue(listing.indexOf("[DIR]  Essentials/") < listing.indexOf("[FILE] a.jar"), listing);
        assertTrue(tools.listDirectory(new JsonObject()).text().startsWith("./"));
        assertThrows(ToolException.class, () -> tools.listDirectory(args("path", "plugins/a.jar")));
    }

    @Test
    void deletesFilesButNotFolders() throws Exception {
        Files.createDirectories(root.resolve("world"));
        Files.writeString(root.resolve("old.jar"), "x");
        tools.deleteFile(args("path", "old.jar"));
        assertFalse(Files.exists(root.resolve("old.jar")));
        assertThrows(ToolException.class, () -> tools.deleteFile(args("path", "world")));
        assertThrows(ToolException.class, () -> tools.deleteFile(args("path", "missing.txt")));
        assertTrue(Files.isDirectory(root.resolve("world")));
    }

    @Test
    void tailsLogsWithFilter() throws Exception {
        Path log = root.resolve("latest.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append(i % 10 == 0 ? "[ERROR] boom " : "[INFO] line ").append(i).append('\n');
        }
        Files.writeString(log, sb.toString());
        assertEquals(List.of("[INFO] line 49", "[ERROR] boom 50"), LogTail.tail(log, 2, null, 1 << 20));
        assertEquals(List.of("[ERROR] boom 40", "[ERROR] boom 50"), LogTail.tail(log, 2, "error", 1 << 20));
        List<String> cut = LogTail.tail(log, 1000, null, 40);
        assertTrue(cut.size() < 50 && cut.get(cut.size() - 1).equals("[ERROR] boom 50"), cut.toString());
        assertFalse(cut.get(0).isEmpty());
    }
}
