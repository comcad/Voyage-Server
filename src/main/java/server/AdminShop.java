package server;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.json.JsonData;
import tools.PacketCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NPCs' shops from the customs folder: server/admin-shops.json, which Voyage-Editor writes when an NPC with a shop is
 * published. Each NPC listed there has { items: { "0": { item, price, max, off }, ... }, wish, closed }: a price above
 * 0 is what a player pays for one (up to max at a time), 0 or below what the NPC pays for one of theirs; off: shown
 * greyed and not traded; wish: leaving, players are asked what else they want; closed: it isn't doing business. The
 * file is read again when it changes, so a published shop is in the game at once.
 * <p>
 * Talked to, an NPC with a shop opens it, unless the customs folder has a script of its own for it (a dialogue made in
 * the editor), which opens it with cm.openAdminShop(). The client knows each row by its number (sn) only, so a player's
 * visit keeps the list they were sent, and a trade is checked against that and against the shop as it is now.
 */
public final class AdminShop {
    private static final Logger log = LoggerFactory.getLogger(AdminShop.class);
    /** What the client is told of a trade: done, and the refusals it has words for. */
    public static final byte OK = 0, NOT_BARTERED = 1, BEGGING = 4, TOO_MANY_MESOS = 5, NOT_ANY_MORE = 6, GREEDY = 9, BUSY = 10;

    /** A row of a shop: its number, the item, the price (above 0 the player buys, else sells), greyed, the most at once. */
    public record Entry(int sn, int itemId, int price, boolean off, int max) {
    }

    /** A player at a shop: whose and on which map, and the list they were sent (null once the window is closed). */
    public static final class Visit {
        private final int npcId, mapId;
        private List<Entry> sent;
        private boolean wished;

        private Visit(int npcId, int mapId, List<Entry> sent) {
            this.npcId = npcId;
            this.mapId = mapId;
            this.sent = sent;
        }
    }

    private static long readAt = Long.MIN_VALUE;
    private static Map<Integer, AdminShop> shops = Map.of();

    private final List<Entry> entries;
    private final boolean wish, closed;

    private AdminShop(List<Entry> entries, boolean wish, boolean closed) {
        this.entries = entries;
        this.wish = wish;
        this.closed = closed;
    }

    private static Path customs(String first, String... more) {
        Path customs = DataProviderFactory.customsFolder();
        return customs != null ? customs.resolve(Path.of(first, more)) : null;
    }

    /** The shop the customs folder gives NPC `npcId`, or null when it gives it none. */
    public static synchronized AdminShop of(int npcId) {
        Path file = customs("server", "admin-shops.json");
        if (file == null || !Files.exists(file)) {
            shops = Map.of();
            readAt = Long.MIN_VALUE;
            return null;
        }
        try {
            long at = Files.getLastModifiedTime(file).toMillis();
            if (at != readAt) {
                shops = read(JsonData.read(file, "admin-shops.json"));
                readAt = at;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("The customs folder's NPC shops ({}) can't be read", file, e);
            shops = Map.of();
            readAt = Long.MIN_VALUE;
        }
        return shops.get(npcId);
    }

    private static Map<Integer, AdminShop> read(Data all) {
        Map<Integer, AdminShop> out = new HashMap<>();
        for (Data npc : all.getChildren()) {
            int id;
            try {
                id = Integer.parseInt(npc.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            List<Data> rows = new ArrayList<>();
            Data items = npc.getChildByPath("items");
            if (items != null) {
                for (Data d : items.getChildren()) {
                    if (d.getName().matches("\\d{1,4}")) {
                        rows.add(d);
                    }
                }
            }
            rows.sort(Comparator.comparingInt(d -> Integer.parseInt(d.getName())));
            List<Entry> entries = new ArrayList<>();
            for (Data d : rows) {
                int item = DataTool.getInt("item", d, 0);
                if (item >= 1000000 && item <= 9999999 && entries.size() < 400) {
                    int max = Math.max(1, Math.min(32767, DataTool.getInt("max", d, 1)));
                    entries.add(new Entry(entries.size() + 1, item, DataTool.getInt("price", d, 0), DataTool.getInt("off", d, 0) != 0, max));
                }
            }
            out.put(id, new AdminShop(List.copyOf(entries), DataTool.getInt("wish", npc, 0) != 0, DataTool.getInt("closed", npc, 0) != 0));
        }
        return out;
    }

    /** Whether the customs folder has a script of its own for NPC `npcId` (which then runs in place of opening its shop). */
    public static boolean hasOwnScript(int npcId) {
        Path script = customs("scripts", "npc", npcId + ".js");
        return script != null && Files.exists(script);
    }

    /**
     * Opens NPC `npcId`'s shop for the player (a closed one, or none: it says it isn't doing business). Not while an NPC
     * shop of the game's own is open, as the client would not show it.
     */
    public static void open(Client c, int npcId) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        if (chr.getShop() != null) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        AdminShop shop = of(npcId);
        List<Entry> list = shop == null || shop.closed ? List.of() : shop.entries;
        chr.setAdminShop(new Visit(npcId, chr.getMapId(), list.isEmpty() ? null : list));
        c.sendPacket(PacketCreator.adminShop(npcId, list, shop != null && shop.wish));
    }

    /** The client asks for the list again (after a refusal that closed the window): only of the shop it was at, there. */
    public static void reopen(Client c, int npcId) {
        Visit v = c.getPlayer().getAdminShop();
        if (v != null && v.npcId == npcId && v.mapId == c.getPlayer().getMapId()) {
            open(c, npcId);
        }
    }

    /** The window is closed. */
    public static void closed(Client c) {
        Visit v = c.getPlayer().getAdminShop();
        if (v != null) {
            v.sent = null;
        }
    }

    /** What the player said they want, asked on leaving a shop that asks: noted in the log, once a visit. */
    public static void wish(Client c, int itemId) {
        Visit v = c.getPlayer().getAdminShop();
        if (v == null || v.wished) {
            return;
        }
        v.wished = true;
        log.info("{} wishes NPC {} had item {} ({})", c.getPlayer().getName(), v.npcId, itemId, ItemInformationProvider.getInstance().getName(itemId));
    }

    /**
     * A trade: row `sn` of the list the player was sent, `count` of it (bought from the NPC, or sold to it from inventory
     * slot `slot`). Answered with exactly one result, as the client waits for it; nothing is answered when no window is
     * open, as the client then waits for nothing and a result would end its session.
     */
    public static void trade(Client c, int sn, short count, short slot) {
        Visit v = c.getPlayer().getAdminShop();
        if (v == null || v.sent == null) {
            return;
        }
        c.sendPacket(PacketCreator.adminShopResult(tradeOf(c, v, sn, count, slot)));
    }

    private static byte tradeOf(Client c, Visit v, int sn, short count, short slot) {
        Character chr = c.getPlayer();
        Entry e = v.sent.stream().filter(x -> x.sn() == sn).findFirst().orElse(null);
        if (e == null) {
            return NOT_BARTERED;
        }
        if (chr.getMapId() != v.mapId) {
            return BUSY;
        }
        AdminShop now = of(v.npcId);
        if (now == null || now.closed) {
            return BUSY;
        }
        if (e.off() || now.entries.stream().noneMatch(x -> x.itemId() == e.itemId() && x.price() == e.price() && !x.off())) {
            return NOT_ANY_MORE;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int id = e.itemId(), type = id / 1000000;
        if (ii.getName(id) == null) {
            return NOT_ANY_MORE;
        }
        // the client asks how many only of what stacks (a Use, Set-up or Etc item, but no throwing star or bullet)
        boolean stacks = type >= 2 && type <= 4 && !ItemConstants.isRechargeable(id);
        if (count < 1 || !stacks && count != 1) {
            return GREEDY;
        }
        if (e.price() >= 1) {
            if (count > e.max()) {
                return GREEDY;
            }
            long cost = (long) e.price() * count;
            if (cost > chr.getMeso()) {
                return BEGGING;
            }
            short give = ItemConstants.isRechargeable(id) ? ii.getSlotMax(c, id) : count;
            if (!InventoryManipulator.checkSpace(c, id, give, "")) {
                return GREEDY;
            }
            InventoryManipulator.addById(c, id, give, "", ItemConstants.isPet(id) ? Pet.createPet(id) : -1);
            chr.gainMeso((int) -cost, false);
            return OK;
        }
        InventoryType inv = InventoryType.getByType((byte) type);
        Item item = inv != null && slot >= 1 ? chr.getInventory(inv).getItem(slot) : null;
        if (item == null || item.getItemId() != id || item.getQuantity() < 1 || item.getPetId() > -1
                || ii.isQuestItem(id) || ii.isCash(id) || (item.getFlag() & ItemConstants.LOCK) != 0) {
            return NOT_BARTERED;
        }
        if (stacks && count > item.getQuantity()) {
            return GREEDY;
        }
        long gain = (long) -e.price() * count;
        if (chr.getMeso() + gain > Integer.MAX_VALUE) {
            return TOO_MANY_MESOS;
        }
        // a throwing star or bullet goes with all it holds, as one
        InventoryManipulator.removeFromSlot(c, inv, slot, stacks ? count : item.getQuantity(), false);
        if (gain > 0) {
            chr.gainMeso((int) gain, false);
        }
        return OK;
    }
}
