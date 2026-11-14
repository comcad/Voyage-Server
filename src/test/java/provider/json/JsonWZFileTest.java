package provider.json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.DataDirectoryEntry;
import provider.DataProvider;
import provider.DataTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonWZFileTest {
    @TempDir
    Path dir;
    private Path assets, custom;

    private static void put(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @BeforeEach
    void files() throws IOException {
        assets = dir.resolve("assets/Item");
        custom = dir.resolve("custom/Item");
        put(assets.resolve("Etc/0400.img.json"), "{\"04000000\":{\"info\":{\"price\":1}}}");
        put(assets.resolve("Etc/0401.img.json"), "{\"04010000\":{\"info\":{\"price\":5}}}");
        // the pictures of an .img sit in a folder of its name beside it: not a folder of .img files
        put(assets.resolve("Etc/0400.img/04000000/info/icon.png"), "");
    }

    @Test
    void assetsAloneAreReadAsTheyAre() {
        DataProvider items = new JsonWZFile(assets);
        assertEquals(1, DataTool.getInt("04000000/info/price", items.getData("Etc/0400.img")));
        DataDirectoryEntry etc = (DataDirectoryEntry) items.getRoot().getEntry("Etc");
        assertEquals(2, etc.getFiles().size());
        assertEquals(0, etc.getSubdirectories().size());
        assertNull(items.getData("Etc/0499.img"));
    }

    @Test
    void aCustomFileStandsInForTheAssetsOwn() throws IOException {
        put(custom.resolve("Etc/0400.img.json"), "{\"04000000\":{\"info\":{\"price\":900}}}");
        DataProvider items = new JsonWZFile(assets, custom);
        assertEquals(900, DataTool.getInt("04000000/info/price", items.getData("Etc/0400.img")));
        assertEquals(5, DataTool.getInt("04010000/info/price", items.getData("Etc/0401.img")));
        assertEquals(2, ((DataDirectoryEntry) items.getRoot().getEntry("Etc")).getFiles().size());
    }

    @Test
    void whatOnlyTheCustomFolderHasIsListedToo() throws IOException {
        put(custom.resolve("Etc/0499.img.json"), "{\"04990000\":{\"info\":{\"price\":7}}}");
        put(custom.resolve("Special/0999.img.json"), "{}");
        DataProvider items = new JsonWZFile(assets, custom);
        assertEquals(3, ((DataDirectoryEntry) items.getRoot().getEntry("Etc")).getFiles().size());
        assertNotNull(((DataDirectoryEntry) items.getRoot().getEntry("Special")).getEntry("0999.img"));
        assertEquals(7, DataTool.getInt("04990000/info/price", items.getData("Etc/0499.img")));
    }

    @Test
    void aFilePublishedWhileRunningIsReadWhenNextAskedFor() throws IOException {
        DataProvider items = new JsonWZFile(assets, custom);   // no custom folder yet
        assertEquals(1, DataTool.getInt("04000000/info/price", items.getData("Etc/0400.img")));
        put(custom.resolve("Etc/0400.img.json"), "{\"04000000\":{\"info\":{\"price\":42}}}");
        assertEquals(42, DataTool.getInt("04000000/info/price", items.getData("Etc/0400.img")));
        Files.delete(custom.resolve("Etc/0400.img.json"));
        assertEquals(1, DataTool.getInt("04000000/info/price", items.getData("Etc/0400.img")));
    }
}
