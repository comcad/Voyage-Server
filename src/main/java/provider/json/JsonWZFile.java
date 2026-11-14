package provider.json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataProvider;
import provider.wz.WZDirectoryEntry;
import provider.wz.WZFileEntry;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One WZ file's data as the client's assets hold it: a folder (assets/Map) with one JSON per .img
 * (Map/Map1/100000000.img.json), next to the folders of pictures the client loads (Map/Map1/100000000.img/...),
 * which the server leaves alone.
 *
 * The assets' customs folder holds the same layout (assets/customs/Map/...): an .img found there is read in place
 * of the assets' own, and one only it has is there as well. That is where Voyage-Editor publishes to.
 */
public final class JsonWZFile implements DataProvider {
    private static final Logger log = LoggerFactory.getLogger(JsonWZFile.class);
    private final Path root;
    private final Path custom;
    private final WZDirectoryEntry rootForNavigation;

    public JsonWZFile(Path root) {
        this(root, null);
    }

    /** custom: the same WZ file's folder under the customs folder (it need not exist yet), or null for none. */
    public JsonWZFile(Path root, Path custom) {
        this.root = root;
        this.custom = custom;
        this.rootForNavigation = new WZDirectoryEntry(root.getFileName().toString(), 0, 0, null);
        if (Files.isDirectory(root)) {
            fill(root, rootForNavigation);
        }
        if (custom != null && Files.isDirectory(custom)) {
            fill(custom, rootForNavigation);
        }
    }

    private void fill(Path dir, WZDirectoryEntry into) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".img.json")) {
                    String name = fileName.substring(0, fileName.length() - 5);
                    if (into.getEntry(name) == null) {
                        into.addFile(new WZFileEntry(name, 0, 0, into));
                    }
                } else if (!fileName.endsWith(".img") && Files.isDirectory(path)) {
                    // a folder both have is listed once, with what each holds
                    WZDirectoryEntry sub = into.getEntry(fileName) instanceof WZDirectoryEntry had ? had : null;
                    if (sub == null) {
                        sub = new WZDirectoryEntry(fileName, 0, 0, into);
                        into.addDirectory(sub);
                    }
                    fill(path, sub);
                }
            }
        } catch (IOException e) {
            log.warn("Can not open file/directory at " + dir.toAbsolutePath());
        }
    }

    @Override
    public Data getData(String path) {
        Path file = custom != null ? custom.resolve(path + ".json") : null;
        if (file == null || !Files.exists(file)) {
            file = root.resolve(path + ".json");
        }
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JsonData.read(file, path.substring(path.lastIndexOf('/') + 1));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Datafile " + path + " in " + root.toAbsolutePath() + " can not be read", e);
        }
    }

    @Override
    public DataDirectoryEntry getRoot() {
        return rootForNavigation;
    }
}
