package org.fengfei.lanproxy.client.handlers;

import org.fengfei.lanproxy.client.ClientChannelMannager;
import org.fengfei.lanproxy.client.listener.ChannelStatusListener;
import org.fengfei.lanproxy.client.listener.ProxyChannelBorrowListener;
import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.protocol.Constants;
import org.fengfei.lanproxy.protocol.ProxyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 处理server.host:server.port的消息
 * @author fengfei
 *
 */
public class ClientChannelHandler extends SimpleChannelInboundHandler<ProxyMessage> {

    private static Logger logger = LoggerFactory.getLogger(ClientChannelHandler.class);

    // 连接内网服务
    private Bootstrap bootstrap;

    // 连接公网服务器
    private Bootstrap proxyBootstrap;

    private ChannelStatusListener channelStatusListener;

    public ClientChannelHandler(Bootstrap bootstrap, Bootstrap proxyBootstrap, ChannelStatusListener channelStatusListener) {
        this.bootstrap = bootstrap;
        this.proxyBootstrap = proxyBootstrap;
        this.channelStatusListener = channelStatusListener;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProxyMessage proxyMessage) throws Exception {
        logger.debug("recieved proxy message, type is {}", proxyMessage.getType());
        switch (proxyMessage.getType()) {
            case ProxyMessage.TYPE_CONNECT:
                handleConnectMessage(ctx, proxyMessage);
                break;
            case ProxyMessage.TYPE_DISCONNECT:
                handleDisconnectMessage(ctx, proxyMessage);
                break;
            case ProxyMessage.P_TYPE_TRANSFER:
                handleTransferMessage(ctx, proxyMessage);
                break;
            default:
                break;
        }
    }

    private void handleTransferMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        Channel realServerChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (realServerChannel != null) {
            ByteBuf buf = ctx.alloc().buffer(proxyMessage.getData().length);
            buf.writeBytes(proxyMessage.getData());
            logger.debug("write data to real server, {}", realServerChannel);
            realServerChannel.writeAndFlush(buf);
        }
    }

    /**
     * 断开内网连接
     * @param ctx
     * @param proxyMessage
     */
    private void handleDisconnectMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        // 连接内网channel
        Channel realServerChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        logger.debug("handleDisconnectMessage, {}", realServerChannel);
        if (realServerChannel != null) {
            ctx.channel().attr(Constants.NEXT_CHANNEL).remove();
            // 当用户访问公网端口到UserChannelHandler时会建立channel连接，在断开时，会给ClientChannelHandler发送消息，断开realServerChannel的连接，但是本地机器与公网机器的ctx.channel()不会断开，可以放进proxyChannelPool中，供以后使用
            ClientChannelMannager.returnProxyChanel(ctx.channel());
            realServerChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 连接到本地内网服务
     * @param ctx
     * @param proxyMessage
     */
    private void handleConnectMessage(ChannelHandlerContext ctx, ProxyMessage proxyMessage) {
        // 连接到公网channel
        final Channel cmdChannel = ctx.channel();
        final String userId = proxyMessage.getUri();
        // 127.0.0.1:22
        String[] serverInfo = new String(proxyMessage.getData()).split(":");
        String ip = serverInfo[0];
        int port = Integer.parseInt(serverInfo[1]);
        bootstrap.connect(ip, port).addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {

                // 连接后端服务器成功
                if (future.isSuccess()) {
                    // 连接到内网服务的channel
                    final Channel realServerChannel = future.channel();
                    logger.debug("connect realserver success, {}", realServerChannel);

                    realServerChannel.config().setOption(ChannelOption.AUTO_READ, false);

                    // 获取一个连接到server.bind:server.port的channel
                    ClientChannelMannager.borrowProxyChanel(proxyBootstrap, new ProxyChannelBorrowListener() {

                        @Override
                        public void success(Channel channel) {
                            // 连接绑定
                            channel.attr(Constants.NEXT_CHANNEL).set(realServerChannel);
                            realServerChannel.attr(Constants.NEXT_CHANNEL).set(channel);

                            // 远程绑定
                            ProxyMessage proxyMessage = new ProxyMessage();
                            proxyMessage.setType(ProxyMessage.TYPE_CONNECT);
                            proxyMessage.setUri(userId + "@" + Config.getInstance().getStringValue("client.key"));
                            channel.writeAndFlush(proxyMessage);

                            realServerChannel.config().setOption(ChannelOption.AUTO_READ, true);
                            ClientChannelMannager.addRealServerChannel(userId, realServerChannel);
                            ClientChannelMannager.setRealServerChannelUserId(realServerChannel, userId);
                        }

                        @Override
                        public void error(Throwable cause) {
                            ProxyMessage proxyMessage = new ProxyMessage();
                            proxyMessage.setType(ProxyMessage.TYPE_DISCONNECT);
                            proxyMessage.setUri(userId);
                            cmdChannel.writeAndFlush(proxyMessage);
                        }
                    });

                } else {
                    ProxyMessage proxyMessage = new ProxyMessage();
                    proxyMessage.setType(ProxyMessage.TYPE_DISCONNECT);
                    proxyMessage.setUri(userId);
                    cmdChannel.writeAndFlush(proxyMessage);
                }
            }
        });
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel realServerChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
        if (realServerChannel != null) {
            realServerChannel.config().setOption(ChannelOption.AUTO_READ, ctx.channel().isWritable());
        }

        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        // 控制连接
        if (ClientChannelMannager.getCmdChannel() == ctx.channel()) {
            ClientChannelMannager.setCmdChannel(null);
            ClientChannelMannager.clearRealServerChannels();
            // 等待一段时间，重连公网服务（断线重连？）
            channelStatusListener.channelInactive(ctx);
        } else {
            // 数据传输连接
            Channel realServerChannel = ctx.channel().attr(Constants.NEXT_CHANNEL).get();
            if (realServerChannel != null && realServerChannel.isActive()) {
                realServerChannel.close();
            }
        }

        ClientChannelMannager.removeProxyChanel(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("exception caught", cause);
        super.exceptionCaught(ctx, cause);
    }

}