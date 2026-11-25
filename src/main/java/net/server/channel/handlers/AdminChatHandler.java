package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.packet.Packet;
import server.ChatLogger;
import tools.PacketCreator;

/**
 * @author kevintjuh93
 */
public class AdminChatHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (!c.getPlayer().isGM()) {//if ( (signed int)CWvsContext::GetAdminLevel((void *)v294) > 2 )
            return;
        }
        // as the client sends it: the scope (0 world, 1 channel, 2 map), the notice type (0 notice, 1 alert, 4 slide),
        // then the message
        byte mode = p.readByte();
        byte type = p.readByte();
        String message = p.readString();
        Packet packet = PacketCreator.serverNotice(type, message);
        switch (mode) {
            case 0 -> {// /alertall, /noticeall, /slideall
                c.getWorldServer().broadcastPacket(packet);
                ChatLogger.log(c, "Alert All", message);
            }
            case 1 -> {// /alertch, /noticech, /slidech
                c.getChannelServer().broadcastPacket(packet);
                ChatLogger.log(c, "Alert Ch", message);
            }
            case 2 -> {// /alertm /alertmap, /noticem /noticemap, /slidem /slidemap
                c.getPlayer().getMap().broadcastMessage(packet);
                ChatLogger.log(c, "Alert Map", message);
            }
        }
    }
}
