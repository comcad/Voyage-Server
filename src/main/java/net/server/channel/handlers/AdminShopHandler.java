package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.AdminShop;

/**
 * An NPC's shop window (AdminShop): 0 asks for the list again, 1 trades a row, 2 is the window closing, 3 is what the
 * player said they want on leaving.
 */
public final class AdminShopHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        if (c.getPlayer() == null) {
            return;
        }
        switch (p.readByte()) {
            case 0 -> AdminShop.reopen(c, p.readInt());
            case 1 -> {
                int sn = p.readInt();
                short count = p.readShort();
                AdminShop.trade(c, sn, count, p.readShort());
            }
            case 2 -> AdminShop.closed(c);
            case 3 -> AdminShop.wish(c, p.readInt());
            default -> {
            }
        }
    }
}
