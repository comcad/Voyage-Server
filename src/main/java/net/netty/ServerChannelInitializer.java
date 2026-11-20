package net.netty;

import client.Client;
import config.YamlConfig;
import constants.net.ServerConstants;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import net.encryption.ClientCyphers;
import net.encryption.InitializationVector;
import net.encryption.PacketCodec;
import net.packet.logging.InPacketLogger;
import net.packet.logging.OutPacketLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ServerChannelInitializer extends ChannelInitializer<SocketChannel> {
    private static final Logger log = LoggerFactory.getLogger(ServerChannelInitializer.class);
    private static final int IDLE_TIME_SECONDS = 30;
    private static final boolean LOG_PACKETS = YamlConfig.config.server.USE_DEBUG_SHOW_PACKET;
    private static final ChannelHandler sendPacketLogger = new OutPacketLogger();
    private static final ChannelHandler receivePacketLogger = new InPacketLogger();

    static final AtomicLong sessionId = new AtomicLong(7777);

    String getRemoteAddress(Channel channel) {
        String remoteAddress = "null";
        try {
            remoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        } catch (NullPointerException npe) {
            log.warn("Unable to get remote address from netty Channel: {}", channel, npe);
        }

        return remoteAddress;
    }

    void initPipeline(SocketChannel socketChannel, Client client) {
        initPipeline(socketChannel, client, () -> {});
    }

    /**
     * The hello and the game's handlers. With AUTHENTICATED_CLIENT on they wait for the Voyage client's key
     * (ClientKeyHandler): a connection without it is closed, and `refused` ends its session as its own close would.
     * Off, they go at once, and a key sent anyway is taken off ahead of the game's traffic.
     */
    void initPipeline(SocketChannel socketChannel, Client client, Runnable refused) {
        final InitializationVector sendIv = InitializationVector.generateSend();
        final InitializationVector recvIv = InitializationVector.generateReceive();
        if (YamlConfig.config.server.AUTHENTICATED_CLIENT) {
            socketChannel.pipeline().addLast("ClientKey", new ClientKeyHandler(true, () -> {
                writeInitialUnencryptedHelloPacket(socketChannel, sendIv, recvIv);
                setUpHandlers(socketChannel.pipeline(), sendIv, recvIv, client);
            }, refused));
            return;
        }
        writeInitialUnencryptedHelloPacket(socketChannel, sendIv, recvIv);
        setUpHandlers(socketChannel.pipeline(), sendIv, recvIv, client);
        socketChannel.pipeline().addFirst("ClientKey", new ClientKeyHandler(false, null, null));
    }

    private void writeInitialUnencryptedHelloPacket(SocketChannel socketChannel, InitializationVector sendIv, InitializationVector recvIv) {
        socketChannel.writeAndFlush(Unpooled.wrappedBuffer(PacketCreator.getHello(ServerConstants.VERSION, sendIv, recvIv).getBytes()));
    }

    private void setUpHandlers(ChannelPipeline pipeline, InitializationVector sendIv, InitializationVector recvIv,
                               Client client) {
        pipeline.addLast("IdleStateHandler", new IdleStateHandler(0, 0, IDLE_TIME_SECONDS));
        pipeline.addLast("PacketCodec", new PacketCodec(ClientCyphers.of(sendIv, recvIv)));
        pipeline.addLast("Client", client);

        if (LOG_PACKETS) {
            pipeline.addBefore("Client", "SendPacketLogger", sendPacketLogger);
            pipeline.addBefore("Client", "ReceivePacketLogger", receivePacketLogger);
        }
    }
}
