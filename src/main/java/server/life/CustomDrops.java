package server.life;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.json.JsonData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

/**
 * Monsters' drops from the customs folder: server/mob-drops.json, which Voyage-Editor writes when a monster with
 * drops of its own is published. A monster listed there drops what it says, in place of its drops in the database
 * (an empty list: nothing); one that isn't keeps the database's. Each entry is { item (0: mesos), chance (out of
 * 1000000), min, max, quest (0: any time; else only while that quest is under way) }. The file is read again when it
 * changes, so a published change takes effect with the drops reloaded (!reloaddrops) as well as after a restart.
 */
public final class CustomDrops {
    private static final Logger log = LoggerFactory.getLogger(CustomDrops.class);
    private static long readAt = Long.MIN_VALUE;
    private static Data drops;

    private CustomDrops() {
    }

    private static Path file() {
        Path customs = DataProviderFactory.customsFolder();
        return customs != null ? customs.resolve("server").resolve("mob-drops.json") : null;
    }

    /** The drops the customs folder gives monster `mobId`, or null when it says nothing of it. */
    public static synchronized List<MonsterDropEntry> of(int mobId) {
        Path file = file();
        if (file == null || !Files.exists(file)) {
            drops = null;
            return null;
        }
        try {
            long at = Files.getLastModifiedTime(file).toMillis();
            if (at != readAt) {
                drops = JsonData.read(file, "mob-drops.json");
                readAt = at;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("The customs folder's monster drops ({}) can't be read: the database's are used", file, e);
            drops = null;
            readAt = Long.MIN_VALUE;
            return null;
        }
        Data mob = drops != null ? drops.getChildByPath(String.valueOf(mobId)) : null;
        if (mob == null) {
            return null;
        }
        List<MonsterDropEntry> out = new LinkedList<>();
        for (Data d : mob.getChildren()) {
            int item = DataTool.getInt("item", d, 0), chance = DataTool.getInt("chance", d, 0);
            int min = Math.max(1, DataTool.getInt("min", d, 1)), max = Math.max(min, DataTool.getInt("max", d, min));
            int quest = DataTool.getInt("quest", d, 0);
            if (chance > 0 && item >= 0) {
                out.add(new MonsterDropEntry(item, chance, min, max, (short) quest));
            }
        }
        return out;
    }
}
