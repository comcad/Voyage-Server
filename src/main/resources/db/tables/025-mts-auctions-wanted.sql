-- MTS auctions and the wanted list: an auction's highest bid, its bidder and bid count, the NX held (a bid, or a
-- wanted request's price), a request's comment, the request an offered item belongs to, and when a listing ends
-- (milliseconds); what each character bought, sold, lost and cancelled; and wanted requests that ran out since
-- their owner's last visit.
ALTER TABLE mts_items
    ADD COLUMN bid     INT          NOT NULL DEFAULT '0',
    ADD COLUMN bidder  INT          NOT NULL DEFAULT '0',
    ADD COLUMN bids    INT          NOT NULL DEFAULT '0',
    ADD COLUMN held    INT          NOT NULL DEFAULT '0',
    ADD COLUMN comment VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN wanted  INT          NOT NULL DEFAULT '0',
    ADD COLUMN ends    BIGINT       NOT NULL DEFAULT '0';

CREATE TABLE mts_history
(
    id       INT    NOT NULL AUTO_INCREMENT,
    cid      INT    NOT NULL,
    itemid   INT    NOT NULL,
    quantity INT    NOT NULL DEFAULT '1',
    price    INT    NOT NULL DEFAULT '0',
    fee      INT    NOT NULL DEFAULT '0',
    status   INT    NOT NULL DEFAULT '0',
    time     BIGINT NOT NULL DEFAULT '0',
    PRIMARY KEY (id),
    INDEX (cid)
);

CREATE TABLE mts_notices
(
    cid      INT NOT NULL,
    listings INT NOT NULL DEFAULT '0',
    nx       INT NOT NULL DEFAULT '0',
    PRIMARY KEY (cid)
);
