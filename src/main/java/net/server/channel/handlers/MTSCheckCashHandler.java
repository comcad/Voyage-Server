package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

/**
 * The MTS Check button (no fields): the NX and Maple Point balance again.
 */
public final class MTSCheckCashHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        if (c.getPlayer() == null || !c.getPlayer().getCashShop().isOpened()) {
            return;
        }
        c.sendPacket(PacketCreator.showMTSCash(c.getPlayer()));
    }
}
