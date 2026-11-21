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
package server;

import client.inventory.Item;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * An MTS listing as the client is sent it: the item, its listing id, the price and the fee buyers pay on top of it,
 * the seller's name, when it ends, a comment, an auction's bid count, bid increment, highest bid and buy-now price,
 * and its status on MY PAGE (History: 0 Sold, 1 Purchased, 2 Bid Lost, 3 Cancelled; Auction: 1 Exhibit, 2 Bid).
 */
public class MTSItemInfo {
    /** The fee buyers pay on top of a price: 500 NX and 7%. */
    public static final int FEE_PERCENT = 7, FEE_FLAT = 500;

    public static int fee(int price) {
        return FEE_FLAT + (int) ((long) price * FEE_PERCENT / 100);
    }

    /** What a seller gets from a wanted request's price (which already holds the fee). */
    public static int afterFee(int price) {
        return price - (int) ((long) (price - FEE_FLAT) * FEE_PERCENT / 100) - FEE_FLAT;
    }

    private final Item item;
    private final int price;
    private final int id;
    private final int sellerId;
    private final String seller;
    private final long ends;
    private int fee;
    private int bids, step, bid, buyNow, status;
    private String comment = "";

    public MTSItemInfo(Item item, int price, int id, int sellerId, String seller, long ends) {
        this.item = item;
        this.price = price;
        this.id = id;
        this.sellerId = sellerId;
        this.seller = seller == null ? "" : seller;
        this.ends = ends;
        this.fee = fee(price);
    }

    /** A listing with its end date as yyyy-MM-dd. */
    public MTSItemInfo(Item item, int price, int id, int sellerId, String seller, String date) {
        this(item, price, id, sellerId, seller, LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    public Item getItem() {
        return item;
    }

    public int getPrice() {
        return price;
    }

    /** The fee buyers pay on top of the price (0 for a wanted request, whose price already holds it). */
    public int getTaxes() {
        return fee;
    }

    public void setFee(int fee) {
        this.fee = fee;
    }

    public int getID() {
        return id;
    }

    public int getSellerId() {
        return sellerId;
    }

    public String getSeller() {
        return seller;
    }

    public long getEndingDate() {
        return ends;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment == null ? "" : comment;
    }

    public void setAuction(int bids, int step, int bid, int buyNow) {
        this.bids = bids;
        this.step = step;
        this.bid = bid;
        this.buyNow = buyNow;
    }

    public int getBids() {
        return bids;
    }

    public int getStep() {
        return step;
    }

    public int getBid() {
        return bid;
    }

    public int getBuyNow() {
        return buyNow;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }
}
