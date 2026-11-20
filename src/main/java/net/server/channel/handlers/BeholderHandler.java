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

import client.BuffStat;
import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import constants.skills.DarkKnight;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.StatEffect;
import server.maps.Summon;
import tools.PacketCreator;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The Beholder's Aura and Hex, as its owner's client asks for them (it acts with assist type 2): oid, the skill, the
 * action (bit 7 its side), and for Hex the stat it picked (0 weapon def, 1 magic def, 2 accuracy, 3 avoidability, 4
 * weapon attack). The Aura heals its hp; Hex gives that one stat. Each comes at most once per its x seconds; the pose
 * goes to the others on the map (the owner's client played it already).
 */
public final class BeholderHandler extends AbstractPacketHandler {
    private static final BuffStat[] HEX_STATS = {BuffStat.WDEF, BuffStat.MDEF, BuffStat.ACC, BuffStat.AVOID, BuffStat.WATK};
    private static final Map<Integer, Long> lastAura = new ConcurrentHashMap<>(), lastHex = new ConcurrentHashMap<>();

    @Override
    public final void handlePacket(InPacket p, Client c) {
        final Character chr = c.getPlayer();
        final int oid = p.readInt();
        Summon summon = null;
        for (Summon sum : chr.getSummonsValues()) {
            if (sum.getObjectId() == oid) {
                summon = sum;
            }
        }
        if (summon == null || summon.getSkill() != DarkKnight.BEHOLDER) {
            return;
        }
        final int skillId = p.readInt();
        final byte action = p.readByte();
        final Skill skill = SkillFactory.getSkill(skillId);
        final int level = skill == null ? 0 : chr.getSkillLevel(skill);
        if (level <= 0) {
            return;
        }
        final StatEffect effect = skill.getEffect(level);
        final long now = System.currentTimeMillis(), interval = effect.getX() * 1000L - 500;
        if (skillId == DarkKnight.AURA_OF_BEHOLDER) {
            if (now - lastAura.getOrDefault(chr.getId(), 0L) < interval) {
                return;
            }
            lastAura.put(chr.getId(), now);
            chr.addHP(effect.getHp());
        } else if (skillId == DarkKnight.HEX_OF_BEHOLDER) {
            final int k = p.available() > 0 ? p.readByte() : -1;
            if (k < 0 || k >= HEX_STATS.length || now - lastHex.getOrDefault(chr.getId(), 0L) < interval) {
                return;
            }
            final List<Pair<BuffStat, Integer>> only = effect.getStatups().stream().filter(s -> s.getLeft() == HEX_STATS[k]).collect(Collectors.toCollection(ArrayList::new));
            if (only.isEmpty()) {
                return;
            }
            lastHex.put(chr.getId(), now);
            effect.withOnlyStatups(only).applyTo(chr);
        } else {
            return;
        }
        chr.sendPacket(PacketCreator.showOwnBuffEffect(DarkKnight.BEHOLDER, 2));
        chr.getMap().broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), DarkKnight.BEHOLDER, 2), false);
        chr.getMap().broadcastMessage(chr, PacketCreator.summonSkill(chr.getId(), oid, action), false);
    }
}
