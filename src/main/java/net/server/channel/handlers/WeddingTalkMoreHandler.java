/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
import config.YamlConfig;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import scripting.event.EventInstanceManager;
import tools.PacketCreator;

/**
 * A guest's blessing (no fields): the client's Yes to "Do you want to bless this couple?" on the cathedral's blessing
 * step. Counted once per guest while the blessing is open, as the officiant's own blessing is; the client expects no
 * answer.
 */
public final class WeddingTalkMoreHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        EventInstanceManager eim = chr.getEventInstance();
        if (eim == null || chr.getMapId() != MapId.CATHEDRAL_WEDDING_ALTAR) {
            return;
        }
        if (chr.getId() == eim.getIntProperty("groomId") || chr.getId() == eim.getIntProperty("brideId")) {
            return;
        }
        if (eim.getIntProperty("weddingStage") != 1 || eim.getIntProperty("guestBlessings") != 1 || eim.gridCheck(chr) != -1) {
            return;
        }

        eim.gridInsert(chr, 1);
        chr.dropMessage(5, "High Priest John: Your blessings have been added to their love. What a noble act for a lovely couple!");
        if (YamlConfig.config.server.WEDDING_BLESSER_SHOWFX) {
            showBlessing(chr);
        } else {
            showBlessing(eim.getPlayerById(eim.getIntProperty("groomId")));
            showBlessing(eim.getPlayerById(eim.getIntProperty("brideId")));
        }
    }

    private static void showBlessing(Character target) {
        if (target == null) {
            return;
        }
        target.sendPacket(PacketCreator.showSpecialEffect(9));
        target.getMap().broadcastMessage(target, PacketCreator.showForeignEffect(target.getId(), 9), false);
    }
}
