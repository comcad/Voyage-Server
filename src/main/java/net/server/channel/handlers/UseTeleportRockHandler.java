package net.server.channel.handlers;

import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

/**
 * USE_TELEPORT_ROCK: a Use-tab Teleport Rock (232xxxx), as the client sends it: its slot, the item, the way (0 a map,
 * 1 a player), the map or the name, a tick. It goes as the cash rocks do (UseCashItemHandler.teleportRock), and is used
 * up only when the trip goes.
 */
public final class UseTeleportRockHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        short slot = p.readShort();
        int itemId = p.readInt();
        boolean byName = p.readByte() == 1;
        Item rock = c.getPlayer().getInventory(InventoryType.USE).getItem(slot);
        if (rock == null || rock.getItemId() != itemId || itemId / 10000 != 232 || !c.getPlayer().isAlive()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (UseCashItemHandler.teleportRock(c, itemId, byName, p)) {
            InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) 1, false);
        } else {
            c.sendPacket(PacketCreator.enableActions());
        }
    }
}
