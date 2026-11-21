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
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop;
import server.ItemInformationProvider;
import server.MTSItemInfo;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The MTS: fixed-price sales, the Cart, auctions, the wanted list, Transfer Inventory and MY PAGE.
 * <p>
 * Listings are mts_items rows, by tab: 1 for sale, 2 a wanted request, 3 an auction, 5 an item offered to a wanted
 * request (shown only in that request's Buy List). transfer = 1 puts a row in its seller's Transfer Inventory.
 * Buyers pay the price plus 500 NX and 7%. A bid takes the bidder's NX at once and gives it back when they're
 * outbid; when the auction ends the top bidder gets the item and the seller the winning bid. A wanted request holds
 * its price until one of the items offered to it is bought (the seller gets the price less the fee), it's cancelled,
 * or it runs out after 7 days (the NX back to its owner, the offers to their sellers). Fixed-price listings made here
 * go back to their seller after their 7 days too.
 * MY PAGE: 0 the Cart, 1 Offers (your wanted requests), 2 History (mts_history), 3 Auction (yours, and the ones you lead).
 * One request is handled at a time, so two buyers can't both get an item.
 */
public final class MTSHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(MTSHandler.class);
    private static final Object LOCK = new Object();
    private static final int FOR_SALE = 1, WANTED = 2, AUCTION = 3, MY_PAGE = 4, OFFER = 5;
    private static final int CART = 0, OFFERS = 1, HISTORY = 2, MY_AUCTIONS = 3;
    private static final int SOLD = 0, PURCHASED = 1, BID_LOST = 2, CANCELLED = 3, EXHIBIT = 1, BIDDING = 2;
    private static final int LISTING_FEE = 5000, MIN_PRICE = 110, MAX_PRICE = 9999999, MAX_LISTINGS = 10;
    private static final long HOUR = 3600000L, DAY = 24 * HOUR;

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null || !chr.getCashShop().isOpened()) {
            return;
        }
        if (p.available() == 0) {
            c.sendPacket(PacketCreator.showMTSCash(chr));
            return;
        }
        settle();
        int op = p.readByte();
        try {
            synchronized (LOCK) {
                switch (op) {
                    case 2 -> sell(p, c, false);
                    case 3 -> sellToWanted(p, c);
                    case 4 -> askFor(p, c);
                    case 5 -> page(p, c);
                    case 6 -> search(p, c);
                    case 7 -> cancelSale(c, p.readInt());
                    case 8 -> takeHome(c, p.readInt());
                    case 9 -> cartAdd(c, p.readInt());
                    case 10 -> cartRemove(c, p.readInt());
                    case 11 -> buyList(c, p.readInt());
                    case 12 -> buyOffer(c, p.readInt());
                    case 13 -> cancelWanted(c, p.readInt());
                    case 16 -> buy(c, p.readInt());
                    case 17 -> buy(c, p.readInt());
                    case 18 -> sell(p, c, true);
                    case 19 -> {
                        int id = p.readInt(), base = p.readInt(), added = p.readInt();
                        bid(c, id, base, added);
                    }
                    case 20 -> buyNow(c, p.readInt());
                    default -> log.warn("Unhandled OP (MTS): {}, packet: {}", op, p);
                }
            }
        } catch (SQLException e) {
            log.error("MTS request {} failed", op, e);
            c.sendPacket(PacketCreator.MTSResult(0x18, 0));
        }
    }

    // ---------------------------------------------------------------- selling

    /** An item as the listing forms send it: its type and id (null for a pet). */
    private record Listed(int type, int itemId) {}

    private static Listed readListed(InPacket p) {
        int type = p.readByte(), itemId = p.readInt();
        boolean cash = p.readByte() != 0;
        if (cash) {
            p.skip(8);
        }
        p.skip(8); // the end date
        if (type == 1) {
            p.skip(32); // slots, level and the stats
            p.readString();
            p.skip(2 + 10 + (cash ? 0 : 8) + 8 + 4);
        } else if (type == 2) {
            p.readShort();
            p.readString();
            p.readShort();
            if (rechargeable(itemId)) {
                p.skip(8);
            }
        } else {
            return null;
        }
        return new Listed(type, itemId);
    }

    private static boolean rechargeable(int itemId) {
        return itemId / 10000 == 207 || itemId / 10000 == 233;
    }

    /** Why an item can't go up (a reason code the client shows), or 0. */
    private static int refusal(Character chr, Item it, int itemId, int quantity) {
        if (chr.getLevel() < 10) {
            return 0x53;
        }
        if (rechargeable(itemId)) {
            return 0x52;
        }
        if (it == null || it.getItemId() != itemId || quantity < 1 || it.getQuantity() < quantity) {
            return 0x4F;
        }
        if (tradeRestricted(itemId) || it.getExpiration() > 0 || (it.getFlag() & ItemConstants.UNTRADEABLE) != 0) {
            return 0x4F;
        }
        return 0;
    }

    private static boolean tradeRestricted(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        return ii.isCash(itemId) || ii.isUntradeableRestricted(itemId) || ii.isAccountRestricted(itemId) || ii.isQuestItem(itemId) || ii.isPickupRestricted(itemId);
    }

    /** A fixed-price sale (2), or an auction (0x12): the item, its slot, quantity and price (and the auction's terms). */
    private void sell(InPacket p, Client c, boolean auction) throws SQLException {
        Character chr = c.getPlayer();
        Listed li = readListed(p);
        short slot = (short) p.readInt();
        int quantity = p.readInt(), price = p.readInt(), buyNow = 0, hours = 0, step = 0;
        if (auction) {
            buyNow = p.readInt();
            hours = p.readByte() & 0xFF;
            p.readByte();
            step = p.readInt();
        }
        if (li == null) {
            c.sendPacket(PacketCreator.MTSResult(0x1E, 0x4F));
            return;
        }
        if (li.type() == 1) {
            quantity = 1;
        }
        InventoryType invType = ItemConstants.getInventoryType(li.itemId());
        Item it = chr.getInventory(invType).getItem(slot);
        int why = refusal(chr, it, li.itemId(), quantity);
        if (why == 0 && (price < MIN_PRICE || price > MAX_PRICE)) {
            why = 0x4F;
        }
        if (why == 0 && auction && (buyNow < MIN_PRICE || buyNow > MAX_PRICE || step <= 0 || price + step > buyNow || hours < 24 || hours > 168)) {
            why = 0x4F;
        }
        if (why == 0 && chr.getMeso() < LISTING_FEE) {
            why = 0x43;
        }
        if (why != 0) {
            c.sendPacket(PacketCreator.MTSResult(0x1E, why));
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            if (count(con, "seller = ? AND transfer = 0 AND tab IN (1, 3, 5)", chr.getId()) >= MAX_LISTINGS) {
                chr.dropMessage(1, "You already have " + MAX_LISTINGS + " items up for sale.");
                c.sendPacket(PacketCreator.MTSResult(0x1E, 0));
                return;
            }
            Item listed = it.copy();
            listed.setQuantity((short) quantity);
            long ends = System.currentTimeMillis() + (auction ? hours * HOUR : 7 * DAY);
            insert(con, listed, auction ? AUCTION : FOR_SALE, chr.getId(), chr.getName(), price, step, buyNow, ends, "", 0, 0);
        }
        InventoryManipulator.removeFromSlot(c, invType, slot, (short) quantity, false);
        chr.gainMeso(-LISTING_FEE, false);
        c.sendPacket(PacketCreator.MTSConfirmSell());
        refresh(c);
    }

    /** Taking a listing down (7): back to Transfer Inventory, unless an auction already has bids. */
    private void cancelSale(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row r = row(con, id);
            if (r == null || r.seller != chr.getId() || r.transfer != 0 || r.tab == WANTED) {
                c.sendPacket(PacketCreator.MTSResult(0x26, r != null && r.transfer != 0 ? 0x51 : 0));
                return;
            }
            if (r.tab == AUCTION && r.bids > 0) {
                c.sendPacket(PacketCreator.MTSResult(0x26, 0x4E));
                return;
            }
            back(con, id);
            uncart(con, id);
            history(con, chr.getId(), r.itemId, r.quantity, r.price, 0, CANCELLED);
        }
        c.sendPacket(PacketCreator.MTSResult(0x25));
        refresh(c);
    }

    /** Transfer Inventory (8): the item into the bag, if there's room. */
    private void takeHome(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        Item it = null;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE id = ? AND seller = ? AND transfer = 1")) {
            ps.setInt(1, id);
            ps.setInt(2, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    it = item(rs);
                }
            }
            if (it == null) {
                c.sendPacket(PacketCreator.MTSResult(0x28, 0));
                return;
            }
            if (!InventoryManipulator.checkSpace(c, it.getItemId(), it.getQuantity(), it.getOwner())) {
                c.sendPacket(PacketCreator.MTSResult(0x28, 0x47));
                return;
            }
            delete(con, id);
        }
        InventoryType type = ItemConstants.getInventoryType(it.getItemId());
        it.setPosition(chr.getInventory(type).getNextFreeSlot());
        InventoryManipulator.addFromDrop(c, it, false);
        c.sendPacket(PacketCreator.MTSConfirmTransfer(type.getType(), it.getPosition()));
        refresh(c);
    }

    // ---------------------------------------------------------------- buying

    /** Buying a fixed-price listing (0x10), or one from the Cart (0x11). */
    private void buy(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row r = row(con, id);
            if (r == null || r.tab != FOR_SALE || r.transfer != 0 || r.seller == chr.getId()) {
                c.sendPacket(PacketCreator.MTSFailBuy());
                return;
            }
            int fee = MTSItemInfo.fee(r.price), cost = r.price + fee;
            if (nx(chr) < cost) {
                c.sendPacket(PacketCreator.MTSResult(0x18, 0x42));
                return;
            }
            charge(chr, cost);
            credit(con, r.seller, r.price);
            toTransfer(con, id, chr.getId());
            uncart(con, id);
            history(con, r.seller, r.itemId, r.quantity, r.price, 0, SOLD);
            history(con, chr.getId(), r.itemId, r.quantity, r.price, fee, PURCHASED);
        }
        c.sendPacket(PacketCreator.MTSConfirmBuy());
        refresh(c);
    }

    private void cartAdd(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        boolean placed = false;
        try (Connection con = DatabaseConnection.getConnection()) {
            Row r = row(con, id);
            if (r != null && r.tab == FOR_SALE && r.transfer == 0 && r.seller != chr.getId()
                    && count(con, "id IN (SELECT itemid FROM mts_cart WHERE cid = ? AND itemid = ?)", chr.getId(), id) == 0) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO mts_cart (cid, itemid) VALUES (?, ?)")) {
                    ps.setInt(1, chr.getId());
                    ps.setInt(2, id);
                    ps.executeUpdate();
                    placed = true;
                }
            }
        }
        c.sendPacket(PacketCreator.MTSResult(placed ? 0x29 : 0x2A));
        refresh(c);
    }

    private void cartRemove(Client c, int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM mts_cart WHERE itemid = ? AND cid = ?")) {
            ps.setInt(1, id);
            ps.setInt(2, c.getPlayer().getId());
            ps.executeUpdate();
        }
        c.sendPacket(PacketCreator.MTSResult(0x2B));
        refresh(c);
    }

    // ---------------------------------------------------------------- auctions

    /** A bid (0x13): the listing, the highest bid the bidder saw, and what they add (at least the bid increment). */
    private void bid(Client c, int id, int base, int added) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row r = row(con, id);
            if (r == null || r.tab != AUCTION || r.transfer != 0 || r.ends <= System.currentTimeMillis() || r.seller == chr.getId()) {
                c.sendPacket(PacketCreator.MTSResult(0x18, 0));
                return;
            }
            if (r.bidder == chr.getId()) {
                c.sendPacket(PacketCreator.MTSResult(0x3C)); // no consecutive bids
                return;
            }
            int current = r.bid > 0 ? r.bid : r.price, bid = current + added;
            if (base != current || added < r.step || bid >= r.buyNow) {
                c.sendPacket(PacketCreator.MTSResult(0x18, 0));
                refresh(c);
                return;
            }
            int cost = bid + MTSItemInfo.fee(bid);
            if (nx(chr) < cost) {
                c.sendPacket(PacketCreator.MTSResult(0x18, 0x42));
                return;
            }
            charge(chr, cost);
            outbid(con, r);
            try (PreparedStatement ps = con.prepareStatement("UPDATE mts_items SET bid = ?, bidder = ?, bids = bids + 1, held = ? WHERE id = ?")) {
                ps.setInt(1, bid);
                ps.setInt(2, chr.getId());
                ps.setInt(3, cost);
                ps.setInt(4, id);
                ps.executeUpdate();
            }
        }
        refresh(c);
    }

    /** Buying an auction item outright (0x14): the buy-now price and its fee; the top bidder gets their NX back. */
    private void buyNow(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row r = row(con, id);
            if (r == null || r.tab != AUCTION || r.transfer != 0 || r.ends <= System.currentTimeMillis() || r.seller == chr.getId()) {
                c.sendPacket(PacketCreator.MTSResult(0x36));
                return;
            }
            int fee = MTSItemInfo.fee(r.buyNow), cost = r.buyNow + fee;
            int refund = r.bidder == chr.getId() ? r.held : 0;
            if (nx(chr) + refund < cost) {
                c.sendPacket(PacketCreator.MTSResult(0x18, 0x42));
                return;
            }
            if (r.bidder == chr.getId()) {
                credit(con, chr.getId(), r.held); // their own bid back first, no "Bid Lost"
            } else {
                outbid(con, r);
            }
            charge(chr, cost);
            credit(con, r.seller, r.buyNow);
            toTransfer(con, id, chr.getId());
            history(con, r.seller, r.itemId, r.quantity, r.buyNow, 0, SOLD);
            history(con, chr.getId(), r.itemId, r.quantity, r.buyNow, fee, PURCHASED);
        }
        c.sendPacket(PacketCreator.MTSConfirmBuy());
        refresh(c);
    }

    /** The auction's top bidder (if any) gets the NX they put down back, and a "Bid Lost". */
    private static void outbid(Connection con, Row r) throws SQLException {
        if (r.bidder <= 0) {
            return;
        }
        credit(con, r.bidder, r.held);
        history(con, r.bidder, r.itemId, r.quantity, r.bid, MTSItemInfo.fee(r.bid), BID_LOST);
    }

    // ---------------------------------------------------------------- the wanted list

    /** Asking for an item on WANTED (4): the item, the price (fee included, held now), how many, and a comment. */
    private void askFor(InPacket p, Client c) throws SQLException {
        Character chr = c.getPlayer();
        int itemId = p.readInt(), price = p.readInt(), quantity = p.readInt();
        p.readShort();
        String comment = p.readString();
        if (comment.length() > 99) {
            comment = comment.substring(0, 99);
        }
        boolean equip = ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP;
        if (equip) {
            quantity = 1;
        }
        if (chr.getLevel() < 10 || price < MIN_PRICE || price > MAX_PRICE || quantity < 1 || quantity > 999
                || ItemInformationProvider.getInstance().getName(itemId) == null || tradeRestricted(itemId) || rechargeable(itemId)) {
            c.sendPacket(PacketCreator.MTSResult(0x38));
            return;
        }
        if (nx(chr) < price) {
            c.sendPacket(PacketCreator.MTSResult(0x18, 0x42));
            return;
        }
        Item it = equip ? new Equip(itemId, (short) 0) : new Item(itemId, (short) 0, (short) quantity);
        try (Connection con = DatabaseConnection.getConnection()) {
            charge(chr, price);
            insert(con, it, WANTED, chr.getId(), chr.getName(), price, 0, 0, System.currentTimeMillis() + 7 * DAY, comment, 0, price);
        }
        c.sendPacket(PacketCreator.MTSResult(0x37));
        refresh(c);
    }

    /** Offering an item to someone's wanted request (3): it leaves the bag and waits in the request's Buy List. */
    private void sellToWanted(InPacket p, Client c) throws SQLException {
        Character chr = c.getPlayer();
        p.readByte();
        short slot = (short) p.readInt();
        Listed li = readListed(p);
        int wantedId = p.readInt();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row w = row(con, wantedId);
            if (w == null || w.tab != WANTED || w.transfer != 0 || w.ends <= System.currentTimeMillis()) {
                c.sendPacket(PacketCreator.MTSResult(0x20, 0x50)); // cancelled or sold out
                return;
            }
            InventoryType type = ItemConstants.getInventoryType(w.itemId);
            Item it = chr.getInventory(type).getItem(slot);
            int why = refusal(chr, it, w.itemId, w.quantity);
            if (w.seller == chr.getId() || li == null || li.itemId() != w.itemId || why != 0) {
                c.sendPacket(PacketCreator.MTSResult(0x20, why == 0x52 ? 0x52 : 0));
                return;
            }
            Item offered = it.copy();
            offered.setQuantity((short) w.quantity);
            insert(con, offered, OFFER, chr.getId(), chr.getName(), w.price, 0, 0, w.ends, "", wantedId, 0);
            InventoryManipulator.removeFromSlot(c, type, slot, (short) w.quantity, false);
        }
        c.sendPacket(PacketCreator.MTSResult(0x1F));
        refresh(c);
    }

    /** MY PAGE > Offers, Buy (0x0B): the items offered to your request. */
    private void buyList(Client c, int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            Row w = row(con, id);
            if (w == null || w.tab != WANTED || w.seller != c.getPlayer().getId() || w.transfer != 0) {
                c.sendPacket(PacketCreator.MTSResult(0x2E));
                return;
            }
            c.sendPacket(PacketCreator.MTSBuyList(rows(con, "tab = 5 AND wanted = ? AND transfer = 0", id)));
        }
    }

    /** Buying one item from a request's Buy List (0x0C): the request's NX pays for it; the other offers go back. */
    private void buyOffer(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row o = row(con, id), w = o == null ? null : row(con, o.wanted);
            if (o == null || o.tab != OFFER || o.transfer != 0 || w == null || w.tab != WANTED || w.seller != chr.getId() || w.transfer != 0) {
                c.sendPacket(PacketCreator.MTSResult(0x36));
                return;
            }
            int pay = MTSItemInfo.afterFee(w.price);
            credit(con, o.seller, pay);
            toTransfer(con, id, chr.getId());
            returnOffers(con, w.id);
            delete(con, w.id);
            history(con, chr.getId(), w.itemId, w.quantity, w.price, 0, PURCHASED);
            history(con, o.seller, w.itemId, w.quantity, pay, 0, SOLD);
        }
        c.sendPacket(PacketCreator.MTSResult(0x35));
        refresh(c);
    }

    /** MY PAGE > Offers, Cancel (0x0D): the request's NX back, its offers to their sellers. */
    private void cancelWanted(Client c, int id) throws SQLException {
        Character chr = c.getPlayer();
        try (Connection con = DatabaseConnection.getConnection()) {
            Row w = row(con, id);
            if (w == null || w.tab != WANTED || w.seller != chr.getId() || w.transfer != 0) {
                c.sendPacket(PacketCreator.MTSResult(0x32));
                return;
            }
            credit(con, chr.getId(), w.held);
            returnOffers(con, w.id);
            delete(con, w.id);
            history(con, chr.getId(), w.itemId, w.quantity, w.price, 0, CANCELLED);
        }
        c.sendPacket(PacketCreator.MTSResult(0x31));
        refresh(c);
    }

    // ---------------------------------------------------------------- time running out

    /**
     * Auctions, wanted requests and fixed-price listings whose time is up: an auction goes to its top bidder (the
     * seller paid the winning bid) or back to its seller; a request's NX goes back to its owner (told on their next
     * visit) and its offers to their sellers; a listing goes back to its seller.
     */
    public static void settle() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            try (Connection con = DatabaseConnection.getConnection()) {
                List<Row> ended = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE transfer = 0 AND ends > 0 AND ends <= ? AND tab IN (1, 2, 3)")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ended.add(Row.of(rs));
                        }
                    }
                }
                for (Row r : ended) {
                    if (r.tab == AUCTION && r.bidder > 0) {
                        credit(con, r.seller, r.bid);
                        toTransfer(con, r.id, r.bidder);
                        history(con, r.seller, r.itemId, r.quantity, r.bid, 0, SOLD);
                        history(con, r.bidder, r.itemId, r.quantity, r.bid, MTSItemInfo.fee(r.bid), PURCHASED);
                    } else if (r.tab == WANTED) {
                        credit(con, r.seller, r.held);
                        returnOffers(con, r.id);
                        delete(con, r.id);
                        try (PreparedStatement ps = con.prepareStatement("INSERT INTO mts_notices (cid, listings, nx) VALUES (?, 1, ?) ON DUPLICATE KEY UPDATE listings = listings + 1, nx = nx + VALUES(nx)")) {
                            ps.setInt(1, r.seller);
                            ps.setInt(2, r.held);
                            ps.executeUpdate();
                        }
                    } else {
                        back(con, r.id);
                    }
                    uncart(con, r.id);
                }
            } catch (SQLException e) {
                log.error("MTS settling failed", e);
            }
        }
    }

    /** The wanted requests of this character that ran out since its last visit: how many, and the NX given back. */
    public static Packet pendingNotice(int cid) {
        int listings = 0, nx = 0;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT listings, nx FROM mts_notices WHERE cid = ?")) {
                ps.setInt(1, cid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        listings = rs.getInt(1);
                        nx = rs.getInt(2);
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM mts_notices WHERE cid = ?")) {
                ps.setInt(1, cid);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("MTS notices failed", e);
        }
        return PacketCreator.MTSWantedListingOver(nx, listings);
    }

    // ---------------------------------------------------------------- the lists

    private void page(InPacket p, Client c) {
        Character chr = c.getPlayer();
        int tab = p.readInt(), type = p.readInt(), page = p.readInt();
        if (tab != chr.getCurrentTab() || type != chr.getCurrentType()) {
            chr.setSearch(null);
        }
        chr.changeTab(tab);
        chr.changeType(type);
        chr.changePage(page);
        refresh(c);
    }

    /** A search (6): by character name (0) or by item name, on the tab and category given. */
    private void search(InPacket p, Client c) {
        Character chr = c.getPlayer();
        int tab = p.readInt(), type = p.readInt();
        p.readInt();
        int by = p.readInt();
        String text = p.readString();
        chr.changeTab(tab);
        chr.changeType(type);
        chr.changePage(0);
        chr.changeCI(by);
        chr.setSearch(text);
        refresh(c);
    }

    /** The page the character is on, its Transfer Inventory, Not Yet Sold and NX. */
    private static void refresh(Client c) {
        Character chr = c.getPlayer();
        c.sendPacket(list(chr));
        c.sendPacket(PacketCreator.transferInventory(getTransfer(chr.getId())));
        c.sendPacket(PacketCreator.notYetSoldInv(getNotYetSold(chr.getId())));
        c.sendPacket(PacketCreator.showMTSCash(chr));
        c.enableCSActions();
    }

    /** The character's page: FOR SALE, WANTED or AUCTION by category (with its search, if one is on), or MY PAGE. */
    public static Packet list(Character chr) {
        int tab = chr.getCurrentTab(), type = chr.getCurrentType(), page = Math.max(0, chr.getCurrentPage());
        try (Connection con = DatabaseConnection.getConnection()) {
            if (tab == MY_PAGE) {
                return myPage(con, chr.getId(), type, page);
            }
            StringBuilder where = new StringBuilder("tab = ? AND transfer = 0");
            List<Object> args = new ArrayList<>(List.of(tab));
            if (type != 0) {
                where.append(" AND type = ?");
                args.add(type);
            }
            String search = chr.getSearch();
            if (search != null && !search.isEmpty()) {
                if (chr.getCurrentCI() != 0) {
                    StringJoiner ids = new StringJoiner(", ", " AND itemid IN (", ")");
                    String text = search.toLowerCase();
                    int n = 0;
                    for (Pair<Integer, String> item : ItemInformationProvider.getInstance().getAllItems()) {
                        if (n < 2000 && item.getRight() != null && item.getRight().toLowerCase().contains(text)) {
                            ids.add(String.valueOf(item.getLeft()));
                            n++;
                        }
                    }
                    where.append(n > 0 ? ids.toString() : " AND 1 = 0");
                } else {
                    where.append(" AND sellername LIKE ? ESCAPE '!'");
                    args.add("%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
                }
            }
            List<MTSItemInfo> items = rows(con, where + " ORDER BY id DESC LIMIT " + page * 16 + ", 16", args.toArray());
            return PacketCreator.sendMTS(items, tab, type, page, pages(count(con, where.toString(), args.toArray())));
        } catch (SQLException e) {
            log.error("MTS list failed", e);
            return PacketCreator.sendMTS(new ArrayList<>(), tab, type, page, 0);
        }
    }

    private static int pages(int total) {
        return (total + 15) / 16;
    }

    /** MY PAGE: the Cart, your wanted requests, your history, your auctions and the ones you lead. */
    private static Packet myPage(Connection con, int cid, int type, int page) throws SQLException {
        List<MTSItemInfo> items = new ArrayList<>();
        int total;
        String limit = " LIMIT " + page * 16 + ", 16";
        switch (type) {
            case OFFERS -> {
                items = rows(con, "tab = 2 AND seller = ? AND transfer = 0 ORDER BY id DESC" + limit, cid);
                total = count(con, "tab = 2 AND seller = ? AND transfer = 0", cid);
            }
            case HISTORY -> {
                try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_history WHERE cid = ? ORDER BY id DESC" + limit)) {
                    ps.setInt(1, cid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int itemId = rs.getInt("itemid");
                            Item it = ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP ? new Equip(itemId, (short) 0) : new Item(itemId, (short) 0, (short) rs.getInt("quantity"));
                            MTSItemInfo m = new MTSItemInfo(it, rs.getInt("price"), rs.getInt("id"), cid, "", rs.getLong("time"));
                            m.setFee(rs.getInt("fee"));
                            m.setStatus(rs.getInt("status"));
                            items.add(m);
                        }
                    }
                }
                try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_history WHERE cid = ?")) {
                    ps.setInt(1, cid);
                    try (ResultSet rs = ps.executeQuery()) {
                        total = rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }
            case MY_AUCTIONS -> {
                String where = "tab = 3 AND transfer = 0 AND (seller = ? OR bidder = ?)";
                try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE " + where + " ORDER BY id DESC" + limit)) {
                    ps.setInt(1, cid);
                    ps.setInt(2, cid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            MTSItemInfo m = info(rs);
                            m.setStatus(rs.getInt("seller") == cid ? EXHIBIT : BIDDING);
                            items.add(m);
                        }
                    }
                }
                total = count(con, where, cid, cid);
            }
            default -> {
                items = rows(con, "transfer = 0 AND id IN (SELECT itemid FROM mts_cart WHERE cid = ?) ORDER BY id DESC" + limit, cid);
                total = count(con, "transfer = 0 AND id IN (SELECT itemid FROM mts_cart WHERE cid = ?)", cid);
            }
        }
        return PacketCreator.sendMTS(items, MY_PAGE, type, page, pages(total));
    }

    /** Not Yet Sold: your listings, auctions and offers still up. */
    public static List<MTSItemInfo> getNotYetSold(int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {
            return rows(con, "seller = ? AND transfer = 0 AND tab IN (1, 3, 5) ORDER BY id DESC", cid);
        } catch (SQLException e) {
            log.error("MTS Not Yet Sold failed", e);
            return new ArrayList<>();
        }
    }

    /** Transfer Inventory: what's bought, won, cancelled or returned, waiting to be taken home. */
    public static List<MTSItemInfo> getTransfer(int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {
            return rows(con, "seller = ? AND transfer = 1 ORDER BY id DESC", cid);
        } catch (SQLException e) {
            log.error("MTS Transfer Inventory failed", e);
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------- rows

    /** The fields of a listing the rules look at. */
    private record Row(int id, int tab, int seller, int price, int bid, int bidder, int bids, int held, int step, int buyNow,
                       int wanted, int transfer, long ends, int itemId, int quantity) {
        static Row of(ResultSet rs) throws SQLException {
            return new Row(rs.getInt("id"), rs.getInt("tab"), rs.getInt("seller"), rs.getInt("price"), rs.getInt("bid"), rs.getInt("bidder"),
                    rs.getInt("bids"), rs.getInt("held"), rs.getInt("bid_incre"), rs.getInt("buy_now"), rs.getInt("wanted"), rs.getInt("transfer"),
                    rs.getLong("ends"), rs.getInt("itemid"), rs.getInt("quantity"));
        }
    }

    private static Row row(Connection con, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Row.of(rs) : null;
            }
        }
    }

    private static int bind(PreparedStatement ps, Object... args) throws SQLException {
        int k = 1;
        for (Object a : args) {
            if (a instanceof Long l) {
                ps.setLong(k++, l);
            } else if (a instanceof Integer i) {
                ps.setInt(k++, i);
            } else {
                ps.setString(k++, String.valueOf(a));
            }
        }
        return k;
    }

    private static List<MTSItemInfo> rows(Connection con, String where, Object... args) throws SQLException {
        List<MTSItemInfo> items = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM mts_items WHERE " + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(info(rs));
                }
            }
        }
        return items;
    }

    private static int count(Connection con, String where, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM mts_items WHERE " + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** A listing as the client gets it: an auction shows its highest bid (or starting price) with that bid's fee. */
    private static MTSItemInfo info(ResultSet rs) throws SQLException {
        int tab = rs.getInt("tab"), price = rs.getInt("price");
        long ends = rs.getLong("ends");
        if (ends <= 0) {
            ends = legacyEnd(rs.getString("sell_ends"));
        }
        MTSItemInfo m = new MTSItemInfo(item(rs), price, rs.getInt("id"), rs.getInt("seller"), rs.getString("sellername"), ends);
        m.setComment(rs.getString("comment"));
        if (tab == AUCTION) {
            int current = rs.getInt("bid") > 0 ? rs.getInt("bid") : price;
            m.setAuction(rs.getInt("bids"), rs.getInt("bid_incre"), current, rs.getInt("buy_now"));
            m.setFee(MTSItemInfo.fee(current));
        } else if (tab == WANTED || tab == OFFER) {
            m.setFee(0);
        } else {
            m.setFee(MTSItemInfo.fee(price));
        }
        return m;
    }

    /** The end date of a listing made before the end time was kept (yyyy-MM-dd). */
    private static long legacyEnd(String date) {
        try {
            return LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static Item item(ResultSet rs) throws SQLException {
        int id = rs.getInt("itemid");
        Item it;
        if (rs.getInt("type") == 1) {
            Equip e = new Equip(id, (short) 0, -1);
            e.setQuantity((short) 1);
            e.setUpgradeSlots((byte) rs.getInt("upgradeslots"));
            e.setLevel((byte) rs.getInt("level"));
            e.setStr((short) rs.getInt("str"));
            e.setDex((short) rs.getInt("dex"));
            e.setInt((short) rs.getInt("int"));
            e.setLuk((short) rs.getInt("luk"));
            e.setHp((short) rs.getInt("hp"));
            e.setMp((short) rs.getInt("mp"));
            e.setWatk((short) rs.getInt("watk"));
            e.setMatk((short) rs.getInt("matk"));
            e.setWdef((short) rs.getInt("wdef"));
            e.setMdef((short) rs.getInt("mdef"));
            e.setAcc((short) rs.getInt("acc"));
            e.setAvoid((short) rs.getInt("avoid"));
            e.setHands((short) rs.getInt("hands"));
            e.setSpeed((short) rs.getInt("speed"));
            e.setJump((short) rs.getInt("jump"));
            e.setVicious((short) rs.getInt("vicious"));
            e.setItemLevel(rs.getByte("itemlevel"));
            e.setItemExp(rs.getInt("itemexp"));
            e.setRingId(rs.getInt("ringid"));
            it = e;
        } else {
            it = new Item(id, (short) 0, (short) rs.getInt("quantity"));
        }
        it.setOwner(rs.getString("owner"));
        it.setFlag((short) rs.getInt("flag"));
        it.setExpiration(rs.getLong("expiration"));
        it.setGiftFrom(rs.getString("giftFrom"));
        return it;
    }

    private static int insert(Connection con, Item it, int tab, int sellerId, String sellerName, int price, int step, int buyNow, long ends,
                              String comment, int wanted, int held) throws SQLException {
        Equip e = it instanceof Equip ? (Equip) it : null;
        String sql = "INSERT INTO mts_items (tab, type, itemid, quantity, expiration, giftFrom, seller, price, bid_incre, buy_now, upgradeslots, level, "
                + "str, dex, `int`, luk, hp, mp, watk, matk, wdef, mdef, acc, avoid, hands, speed, jump, locked, owner, sellername, sell_ends, vicious, "
                + "flag, itemexp, itemlevel, ringid, isequip, comment, wanted, held, ends) VALUES (" + "?, ".repeat(40) + "?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int k = 1;
            ps.setInt(k++, tab);
            ps.setInt(k++, ItemConstants.getInventoryType(it.getItemId()).getType());
            ps.setInt(k++, it.getItemId());
            ps.setInt(k++, it.getQuantity());
            ps.setLong(k++, it.getExpiration());
            ps.setString(k++, it.getGiftFrom() == null ? "" : it.getGiftFrom());
            ps.setInt(k++, sellerId);
            ps.setInt(k++, price);
            ps.setInt(k++, step);
            ps.setInt(k++, buyNow);
            ps.setInt(k++, e != null ? e.getUpgradeSlots() : 0);
            ps.setInt(k++, e != null ? e.getLevel() : 0);
            for (int v : e != null ? new int[]{e.getStr(), e.getDex(), e.getInt(), e.getLuk(), e.getHp(), e.getMp(), e.getWatk(), e.getMatk(), e.getWdef(),
                    e.getMdef(), e.getAcc(), e.getAvoid(), e.getHands(), e.getSpeed(), e.getJump()} : new int[15]) {
                ps.setInt(k++, v);
            }
            ps.setInt(k++, 0);
            ps.setString(k++, it.getOwner() == null ? "" : it.getOwner());
            ps.setString(k++, sellerName);
            ps.setString(k++, Instant.ofEpochMilli(ends).atZone(ZoneId.systemDefault()).toLocalDate().toString());
            ps.setInt(k++, e != null ? e.getVicious() : 0);
            ps.setInt(k++, it.getFlag());
            ps.setInt(k++, e != null ? e.getItemExp() : 0);
            ps.setInt(k++, e != null ? e.getItemLevel() : 1);
            ps.setInt(k++, e != null ? e.getRingId() : -1);
            ps.setInt(k++, e != null ? 1 : 0);
            ps.setString(k++, comment);
            ps.setInt(k++, wanted);
            ps.setInt(k++, held);
            ps.setLong(k, ends);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void update(Connection con, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    /** A listing into a character's Transfer Inventory (a buyer's, a winner's). */
    private static void toTransfer(Connection con, int id, int owner) throws SQLException {
        update(con, "UPDATE mts_items SET seller = ?, transfer = 1, bidder = 0, held = 0 WHERE id = ?", owner, id);
    }

    /** A listing back into its own seller's Transfer Inventory. */
    private static void back(Connection con, int id) throws SQLException {
        update(con, "UPDATE mts_items SET transfer = 1, bidder = 0, held = 0 WHERE id = ?", id);
    }

    private static void returnOffers(Connection con, int wantedId) throws SQLException {
        update(con, "UPDATE mts_items SET transfer = 1 WHERE tab = 5 AND wanted = ? AND transfer = 0", wantedId);
    }

    private static void delete(Connection con, int id) throws SQLException {
        update(con, "DELETE FROM mts_items WHERE id = ?", id);
    }

    private static void uncart(Connection con, int id) throws SQLException {
        update(con, "DELETE FROM mts_cart WHERE itemid = ?", id);
    }

    private static void history(Connection con, int cid, int itemId, int quantity, int price, int fee, int status) throws SQLException {
        update(con, "INSERT INTO mts_history (cid, itemid, quantity, price, fee, status, time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                cid, itemId, quantity, price, fee, status, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- NX

    private static int nx(Character chr) {
        return chr.getCashShop().getCash(CashShop.NX_PREPAID);
    }

    private static void charge(Character chr, int amount) {
        chr.getCashShop().gainCash(CashShop.NX_PREPAID, -amount);
    }

    /**
     * NX to a character (a seller's pay, a refund): onto the character when it's online anywhere (a channel, the Cash
     * Shop or the MTS), where its own save would otherwise write the old balance back; else onto its account.
     */
    private static void credit(Connection con, int cid, int amount) throws SQLException {
        if (amount <= 0) {
            return;
        }
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterById(cid);
            if (chr != null) {
                chr.getCashShop().gainCash(CashShop.NX_PREPAID, amount);
                return;
            }
        }
        update(con, "UPDATE accounts SET nxPrepaid = nxPrepaid + ? WHERE id = (SELECT accountid FROM characters WHERE id = ?)", amount, cid);
    }
}
