package org.fengfei.lanproxy.server;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.fengfei.lanproxy.protocol.Constants;
import org.fengfei.lanproxy.server.config.ProxyConfig;
import org.fengfei.lanproxy.server.config.ProxyConfig.ConfigChangedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * 代理服务连接管理（代理客户端连接+用户请求连接）
 *
 * @author fengfei
 *
 */
public class ProxyChannelManager {

    private static Logger logger = LoggerFactory.getLogger(ProxyChannelManager.class);

    // map中string对应userId，channel对应用户访问公网端口对应channel
    private static final AttributeKey<Map<String, Channel>> USER_CHANNELS = AttributeKey.newInstance("user_channels");

    private static final AttributeKey<String> REQUEST_LAN_INFO = AttributeKey.newInstance("request_lan_info");

    private static final AttributeKey<List<Integer>> CHANNEL_PORT = AttributeKey.newInstance("channel_port");

    private static final AttributeKey<String> CHANNEL_CLIENT_KEY = AttributeKey.newInstance("channel_client_key");

    // 应该是公网暴露端口对应client key的proxy channel，并且channel中使用attr绑定的是userId和userChannel
    private static Map<Integer, Channel> portCmdChannelMapping = new ConcurrentHashMap<Integer, Channel>();

    // client key --- proxy channel映射
    private static Map<String, Channel> cmdChannels = new ConcurrentHashMap<String, Channel>();

    static {
        ProxyConfig.getInstance().addConfigChangedListener(new ConfigChangedListener() {

            /**
             * 代理配置发生变化时回调
             * 主要是更新维护portCmdChannelMapping中暴露公网port到proxy channel的关系和client key对应proxyChannel到暴露公网ports的关系
             */
            @Override
            public synchronized void onChanged() {
                Iterator<Entry<String, Channel>> ite = cmdChannels.entrySet().iterator();
                while (ite.hasNext()) {
                    // client key --- proxy channel
                    Channel proxyChannel = ite.next().getValue();
                    String clientKey = proxyChannel.attr(CHANNEL_CLIENT_KEY).get();

                    // 去除已经去掉的clientKey配置
                    Set<String> clientKeySet = ProxyConfig.getInstance().getClientKeySet();
                    if (!clientKeySet.contains(clientKey)) {
                        removeCmdChannel(proxyChannel);
                        continue;
                    }

                    if (proxyChannel.isActive()) {
                        // 从新配置文件中获取指定clientKey对应端口列表，后面会去除旧连接使用的端口，最终会剩下新增的端口
                        List<Integer> inetPorts = new ArrayList<Integer>(ProxyConfig.getInstance().getClientInetPorts(clientKey));
                        // 从新配置文件中获取指定clientKey对应端口列表
                        Set<Integer> inetPortSet = new HashSet<Integer>(inetPorts);
                        // 旧的连接关系
                        List<Integer> channelInetPorts = new ArrayList<Integer>(proxyChannel.attr(CHANNEL_PORT).get());

                        synchronized (portCmdChannelMapping) {

                            // 移除旧的连接映射关系
                            for (int chanelInetPort : channelInetPorts) {
                                // 用户请求公网端口channel
                                Channel channel = portCmdChannelMapping.get(chanelInetPort);
                                if (channel == null) {
                                    continue;
                                }

                                // 判断是否是同一个连接对象，有可能之前已经更换成其他client的连接了
                                if (proxyChannel == channel) {
                                    if (!inetPortSet.contains(chanelInetPort)) {

                                        // 移除新配置中不包含的端口
                                        portCmdChannelMapping.remove(chanelInetPort);
                                        proxyChannel.attr(CHANNEL_PORT).get().remove(new Integer(chanelInetPort));
                                    } else {

                                        // 端口已经在改proxyChannel中使用了
                                        inetPorts.remove(new Integer(chanelInetPort));
                                    }
                                }
                            }

                            // 将新配置中增加的外网端口写入到映射配置中
                            for (int inetPort : inetPorts) {
                                portCmdChannelMapping.put(inetPort, proxyChannel);
                                proxyChannel.attr(CHANNEL_PORT).get().add(inetPort);
                            }

                            checkAndClearUserChannels(proxyChannel);
                        }
                    }
                }

                // 打印日志信息
                ite = cmdChannels.entrySet().iterator();
                while (ite.hasNext()) {
                    Entry<String, Channel> entry = ite.next();
                    Channel proxyChannel = entry.getValue();
                    logger.info("proxyChannel config, {}, {}, {} ,{}", entry.getKey(), proxyChannel, getUserChannels(proxyChannel).size(), proxyChannel.attr(CHANNEL_PORT).get());
                }
            }

            /**
             * 检测配置文件连接配置是否与当前配置一致，不一致则关闭当前配置userChannel并清除
             *
             * @param proxyChannel client key 对应 proxy channel
             */
            private void checkAndClearUserChannels(Channel proxyChannel) {
                // Map<userId, 用户访问公网端口对应channel>
                Map<String, Channel> userChannels = getUserChannels(proxyChannel);
                Iterator<Entry<String, Channel>> userChannelIte = userChannels.entrySet().iterator();
                while (userChannelIte.hasNext()) {
                    Entry<String, Channel> entry = userChannelIte.next();
                    Channel userChannel = entry.getValue();
                    String requestLanInfo = getUserChannelRequestLanInfo(userChannel);
                    InetSocketAddress sa = (InetSocketAddress) userChannel.localAddress();
                    String lanInfo = ProxyConfig.getInstance().getLanInfo(sa.getPort());

                    // 判断当前配置中对应外网端口的lan信息是否与正在运行的连接中的lan信息是否一致
                    if (lanInfo == null || !lanInfo.equals(requestLanInfo)) {
                        userChannel.close();

                        // ConcurrentHashMap不会报ConcurrentModificationException异常
                        userChannels.remove(entry.getKey());
                    }
                }
            }
        });
    }

    /**
     * 增加代理服务器端口与代理控制客户端连接的映射关系
     *
     * @param ports
     * @param channel 指client key对应proxy channel
     */
    public static void addCmdChannel(List<Integer> ports, String clientKey, Channel channel) {

        if (ports == null) {
            throw new IllegalArgumentException("port can not be null");
        }

        // 客户端（proxy-client）相对较少，这里同步的比较重
        // 保证服务器对外端口与客户端到服务器的连接关系在临界情况时调用removeChannel(Channel channel)时不出问题
        synchronized (portCmdChannelMapping) {
            for (int port : ports) {
                portCmdChannelMapping.put(port, channel);
            }
        }

        channel.attr(CHANNEL_PORT).set(ports);
        channel.attr(CHANNEL_CLIENT_KEY).set(clientKey);
        // client key 对应的channel与用户访问公网channel对应关系,map<userId,userChannel>
        channel.attr(USER_CHANNELS).set(new ConcurrentHashMap<String, Channel>());
        cmdChannels.put(clientKey, channel);
    }

    /**
     * 代理客户端连接断开后清除关系
     *
     * @param channel
     */
    public static void removeCmdChannel(Channel channel) {
        logger.warn("channel closed, clear user channels, {}", channel);
        if (channel.attr(CHANNEL_PORT).get() == null) {
            return;
        }

        String clientKey = channel.attr(CHANNEL_CLIENT_KEY).get();
        Channel channel0 = cmdChannels.remove(clientKey);
        if (channel != channel0) {
            cmdChannels.put(clientKey, channel);
        }

        List<Integer> ports = channel.attr(CHANNEL_PORT).get();
        for (int port : ports) {
            Channel proxyChannel = portCmdChannelMapping.remove(port);
            if (proxyChannel == null) {
                continue;
            }

            // 在执行断连之前新的连接已经连上来了
            if (proxyChannel != channel) {
                portCmdChannelMapping.put(port, proxyChannel);
            }
        }

        if (channel.isActive()) {
            logger.info("disconnect proxy channel {}", channel);
            channel.close();
        }

        Map<String, Channel> userChannels = getUserChannels(channel);
        Iterator<String> ite = userChannels.keySet().iterator();
        while (ite.hasNext()) {
            Channel userChannel = userChannels.get(ite.next());
            if (userChannel.isActive()) {
                userChannel.close();
                logger.info("disconnect user channel {}", userChannel);
            }
        }
    }

    public static Channel getCmdChannel(Integer port) {
        return portCmdChannelMapping.get(port);
    }

    public static Channel getCmdChannel(String clientKey) {
        return cmdChannels.get(clientKey);
    }

    /**
     * 增加用户连接与代理客户端连接关系
     *
     * @param proxyChannel
     * @param userId
     * @param userChannel
     */
    public static void addUserChannelToCmdChannel(Channel cmdChannel, String userId, Channel userChannel) {
        InetSocketAddress sa = (InetSocketAddress) userChannel.localAddress();
        String lanInfo = ProxyConfig.getInstance().getLanInfo(sa.getPort());
        userChannel.attr(Constants.USER_ID).set(userId);
        userChannel.attr(REQUEST_LAN_INFO).set(lanInfo);
        cmdChannel.attr(USER_CHANNELS).get().put(userId, userChannel);
    }

    /**
     * 删除用户连接与代理客户端连接关系
     *
     * @param proxyChannel
     * @param userId
     * @return
     */
    public static Channel removeUserChannelFromCmdChannel(Channel cmdChannel, String userId) {
        if (cmdChannel.attr(USER_CHANNELS).get() == null) {
            return null;
        }

        synchronized (cmdChannel) {
            return cmdChannel.attr(USER_CHANNELS).get().remove(userId);
        }
    }

    /**
     * 根据代理客户端连接与用户编号获取用户连接
     *
     * @param proxyChannel
     * @param userId
     * @return
     */
    public static Channel getUserChannel(Channel cmdChannel, String userId) {
        return cmdChannel.attr(USER_CHANNELS).get().get(userId);
    }

    /**
     * 获取用户编号
     *
     * @param userChannel
     * @return
     */
    public static String getUserChannelUserId(Channel userChannel) {
        return userChannel.attr(Constants.USER_ID).get();
    }

    /**
     * 获取用户请求的内网IP端口信息
     *
     * @param userChannel
     * @return
     */
    public static String getUserChannelRequestLanInfo(Channel userChannel) {
        return userChannel.attr(REQUEST_LAN_INFO).get();
    }

    /**
     * 获取代理控制客户端连接绑定的所有用户连接
     *
     * @param cmdChannel
     * @return
     */
    public static Map<String, Channel> getUserChannels(Channel cmdChannel) {
        return cmdChannel.attr(USER_CHANNELS).get();
    }

}
