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
package server.life;

import client.Character;
import client.Client;
import server.ShopFactory;
import server.maps.MapObjectType;
import tools.PacketCreator;

import java.lang.ref.WeakReference;

public class NPC extends AbstractLoadedLife {
    private final NPCStats stats;
    /** The player whose client runs this NPC's actions and walks (their NPC_ACTION goes to everyone on the map). */
    private WeakReference<Character> controller = new WeakReference<>(null);

    public NPC(int id, NPCStats stats) {
        super(id);
        this.stats = stats;
    }

    public boolean hasShop() {
        return ShopFactory.getInstance().getShopForNPC(getId()) != null;
    }

    public void sendShop(Client c) {
        ShopFactory.getInstance().getShopForNPC(getId()).sendShop(c);
    }

    public Character getController() {
        return controller.get();
    }

    public void setController(Character chr) {
        controller = new WeakReference<>(chr);
    }

    /**
     * Everyone sees it; one player on the map runs it - the first to see it, until they leave (MapleMap hands it on) -
     * and only they are asked to control it.
     */
    @Override
    public void sendSpawnData(Client client) {
        client.sendPacket(PacketCreator.spawnNPC(this));
        final Character chr = client.getPlayer(), ctrl = getController();
        if (chr == null || ctrl == chr) {
            return;
        }
        if (ctrl == null || ctrl.getMap() != chr.getMap() || !ctrl.isLoggedinWorld()) {
            setController(chr);
            client.sendPacket(PacketCreator.spawnNPCRequestController(this, true));
        }
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.removeNPCController(getObjectId()));
        client.sendPacket(PacketCreator.removeNPC(getObjectId()));
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.NPC;
    }

    public String getName() {
        return stats.getName();
    }
}
