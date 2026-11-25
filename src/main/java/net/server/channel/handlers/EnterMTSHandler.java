/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Item;
import config.YamlConfig;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.MTSItemInfo;
import server.maps.FieldLimit;
import server.maps.MiniDungeonInfo;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


public final class EnterMTSHandler extends AbstractPacketHandler {

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        if (!YamlConfig.config.server.USE_MTS) {
            c.sendPacket(PacketCreator.blockedMessage2(3));   // the client waits on BLOCKED_SERVER
            return;
        }

        if (chr.getEventInstance() != null) {
            c.sendPacket(PacketCreator.serverNotice(5, "Entering Cash Shop or MTS are disabled when registered on an event."));
            c.sendPacket(PacketCreator.blockedMessage2(3));   // the client waits on BLOCKED_SERVER
            return;
        }

        if (MiniDungeonInfo.isDungeonMap(chr.getMapId())) {
            c.sendPacket(PacketCreator.serverNotice(5, "Changing channels or entering Cash Shop or MTS are disabled when inside a Mini-Dungeon."));
            c.sendPacket(PacketCreator.blockedMessage2(3));   // the client waits on BLOCKED_SERVER
            return;
        }

        if (FieldLimit.CANNOTMIGRATE.check(chr.getMap().getFieldLimit())) {
            chr.dropMessage(1, "You can't do it here in this map.");
            c.sendPacket(PacketCreator.blockedMessage2(3));   // the client waits on BLOCKED_SERVER
            return;
        }

        if (!chr.isAlive()) {
            c.sendPacket(PacketCreator.blockedMessage2(3));   // the client waits on BLOCKED_SERVER
            return;
        }
        if (chr.getLevel() < 10) {
            c.sendPacket(PacketCreator.blockedMessage2(5));
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        chr.closePlayerInteractions();
        chr.closePartySearchInteractions();

        chr.unregisterChairBuff();
        Server.getInstance().getPlayerBuffStorage().addBuffsToStorage(chr.getId(), chr.getAllBuffs());
        Server.getInstance().getPlayerBuffStorage().addDiseasesToStorage(chr.getId(), chr.getAllDiseases());
        chr.setAwayFromChannelWorld();
        chr.notifyMapTransferToPartner(-1);
        chr.removeIncomingInvites();
        chr.cancelAllBuffs(true);
        chr.cancelAllDebuffs();
        chr.cancelBuffExpireTask();
        chr.cancelDiseaseExpireTask();
        chr.cancelSkillCooldownTask();
        chr.cancelExpirationTask();

        chr.forfeitExpirableQuests();
        chr.cancelQuestExpirationTask();

        chr.saveCharToDB();

        c.getChannelServer().removePlayer(chr);
        chr.getMap().removePlayer(c.getPlayer());
        try {
            c.sendPacket(PacketCreator.openCashShop(c, true));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        chr.getCashShop().open(true);// xD
        c.enableCSActions();
        chr.changeTab(1);
        chr.changeType(0);
        chr.changePage(0);
        chr.setSearch(null);
        MTSHandler.settle();
        c.sendPacket(MTSHandler.pendingNotice(chr.getId()));
        c.sendPacket(PacketCreator.showMTSCash(chr));
        c.sendPacket(MTSHandler.list(chr));
        c.sendPacket(PacketCreator.transferInventory(MTSHandler.getTransfer(chr.getId())));
        c.sendPacket(PacketCreator.notYetSoldInv(MTSHandler.getNotYetSold(chr.getId())));
    }
}
