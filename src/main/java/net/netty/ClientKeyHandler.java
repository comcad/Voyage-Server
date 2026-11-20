package net.netty;

import config.YamlConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Voyage client's key, ahead of the game's own traffic: the client's relay sends "VOYAGEKEY", the key's length
 * (two bytes, little endian) and the key (UTF-8) as soon as it connects. Required (AUTHENTICATED_CLIENT on), the hello
 * waits for a key matching CLIENT_KEY; anything else - no key, a wrong one, or none within 5 seconds - is closed.
 * Not required, a key sent anyway is taken off and anything else goes on as it came.
 */
public class ClientKeyHandler extends ByteToMessageDecoder {
    private static final Logger log = LoggerFactory.getLogger(ClientKeyHandler.class);
    private static final byte[] MAGIC = "VOYAGEKEY".getBytes(StandardCharsets.US_ASCII);
    private static final int WAIT_SECONDS = 5;

    private final boolean required;
    private final Runnable accepted, refused;
    private ScheduledFuture<?> timeout;
    private boolean done;

    public ClientKeyHandler(boolean required, Runnable accepted, Runnable refused) {
        this.required = required;
        this.accepted = accepted;
        this.refused = refused;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (required) {
            timeout = ctx.executor().schedule(() -> refuse(ctx, "no key in time"), WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (done) {
            in.skipBytes(in.readableBytes());
            return;
        }
        final int start = in.readerIndex(), have = in.readableBytes();
        // as soon as what came differs from the magic, it is no key
        for (int i = 0; i < Math.min(have, MAGIC.length); i++) {
            if (in.getByte(start + i) != MAGIC[i]) {
                noKey(ctx);
                return;
            }
        }
        if (have < MAGIC.length + 2) {
            return;
        }
        final int length = in.getUnsignedShortLE(start + MAGIC.length);
        if (have < MAGIC.length + 2 + length) {
            return;
        }
        final byte[] key = new byte[length];
        in.getBytes(start + MAGIC.length + 2, key);
        in.skipBytes(MAGIC.length + 2 + length);
        if (required) {
            if (!keyMatches(key)) {
                refuse(ctx, "a wrong key");
                return;
            }
            if (timeout != null) {
                timeout.cancel(false);
            }
            done = true;
            accepted.run();
        }
        done = true;
        ctx.pipeline().remove(this);
    }

    /** What came isn't a key: refused when one is required, otherwise passed on untouched. */
    private void noKey(ChannelHandlerContext ctx) {
        if (required) {
            refuse(ctx, "no key");
            return;
        }
        done = true;
        ctx.pipeline().remove(this);
    }

    private static boolean keyMatches(byte[] key) {
        final String want = YamlConfig.config.server.CLIENT_KEY;
        if (want == null || want.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), key);
    }

    private void refuse(ChannelHandlerContext ctx, String why) {
        if (done) {
            return;
        }
        done = true;
        if (timeout != null) {
            timeout.cancel(false);
        }
        log.info("Refused a client from {}: {}", ctx.channel().remoteAddress(), why);
        try {
            if (refused != null) {
                refused.run();
            }
        } finally {
            ctx.close();
        }
    }
}
