package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

/**
 * The MTS Charge button (no fields). There's nowhere to buy NX from here, so the client is told to go ahead on its
 * own (it points the player to the website).
 */
public final class MTSChargeHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        if (c.getPlayer() == null || !c.getPlayer().getCashShop().isOpened()) {
            return;
        }
        c.sendPacket(PacketCreator.MTSChargeResult());
    }
}
