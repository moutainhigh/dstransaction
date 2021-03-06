package cn.itweknow.sbrpccorestarter.consumer;


import cn.itweknow.sbrpccorestarter.common.RpcDecoder;
import cn.itweknow.sbrpccorestarter.common.RpcEncoder;
import cn.itweknow.sbrpccorestarter.model.ProviderInfo;
import cn.itweknow.sbrpccorestarter.model.RpcRequest;
import cn.itweknow.sbrpccorestarter.model.RpcResponse;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @author 链接复用代码，请求将被缓存
 * @date 2020/12/26 18:09
 * @description
 */
@ChannelHandler.Sharable
public class RpcClientPool extends SimpleChannelInboundHandler<RpcResponse> {

    private Logger logger = LoggerFactory.getLogger(RpcClientPool.class);

    private static RpcClientPool rpcClientPool;
    static {
        rpcClientPool = new RpcClientPool();
        rpcClientPool.init();
    }

    public static RpcClientPool getRpcClientPool(){
        return rpcClientPool;
    }


    EventLoopGroup workerGroup;
    Bootstrap bootstrap;

    //ChannelFuture channelFuture;
    Map<String,ChannelId> channelMap = new HashMap<>();
    Map<ChannelId,CompletableFuture<String>> requestFuture = new HashMap<>();
    public static ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private CompletableFuture<String> connectFuture;


    /**
     * 用来接收服务器端的返回的。
     */
    private RpcResponse response;


    @PostConstruct
    void init(){
        if (this.workerGroup != null){
            return;
        }
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline()
                                    .addLast(new RpcEncoder(RpcRequest.class))
                                    .addLast(new RpcDecoder(RpcResponse.class))
                                    .addLast(RpcClientPool.this);
                        }
                    });
            //future = new CompletableFuture<>();
            this.workerGroup = workerGroup;
            this.bootstrap = bootstrap;
        } catch (Exception e) {
            logger.error("RpcStarter::client send msg error,", e);
        } finally {
//            workerGroup.shutdownGracefully();
        }
    }

    void connect(ProviderInfo providerInfo){
        try {
            // 连接服务器
            String[] addrInfo = providerInfo.getAddr().split(":");
            String host = addrInfo[0];
            int port = Integer.parseInt(addrInfo[1]);
            logger.info("RpcStarter::Consumer connecting provider >> {}:{}", host,port);
            connectFuture = new CompletableFuture<>();
            ChannelFuture channelFuture = bootstrap.connect(host, port).sync();
            logger.info("RpcStarter::Consumer connected provider >> {}:{}", host,port);
            connectFuture.get();
            //channelFuture.channel().closeFuture().sync();
        } catch (Exception e) {
            logger.error("RpcStarter::Consumer send msg error,", e);
        } finally {
//            workerGroup.shutdownGracefully();
        }
    }



    public RpcResponse send(ProviderInfo providerInfo, RpcRequest request){
        try {
            String addr = providerInfo.getAddr();
            ChannelId channelId = channelMap.get(addr);
            if (channelId == null){
                connect(providerInfo);
                channelId = channelMap.get(addr);
            }
            if (channelId == null){
//                Assert.checkNonNull(channelId);
                throw new RuntimeException();
            }
            Channel channel = channelGroup.find(channelId);
            channel.writeAndFlush(request).sync();
            CompletableFuture future = new CompletableFuture<>();
            requestFuture.put(channelId,future);
            //TODO:线程阻塞住，等待结果
            future.get();
            return response;
        } catch (Exception e) {
            logger.error("RpcStarter::Consumer send msg error,", e);
            return null;
        } finally {

        }
    }

    void close(){
        workerGroup.shutdownGracefully();
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("RpcStarter::Consumer 发起建立连接，通道开启！id={}",ctx.channel().id().asLongText());
        InetSocketAddress inetSocketAddress = (InetSocketAddress)ctx.channel().remoteAddress();
        String key = inetSocketAddress.getHostName() + ":" + inetSocketAddress.getPort();
        channelMap.put(key, ctx.channel().id());
        channelGroup.add(ctx.channel());
        connectFuture.complete("");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("RpcStarter::Consumer 断开连接，通道关闭！id={}",ctx.channel().id().asLongText());
        InetSocketAddress inetSocketAddress = (InetSocketAddress)ctx.channel().remoteAddress();
        String key = inetSocketAddress.getHostName() + ":" + inetSocketAddress.getPort();
        channelMap.remove(key);
        channelGroup.remove(ctx.channel());
        requestFuture.remove(ctx.channel().id());
        //TODO:失效后是否需要close?或许channel会自动close
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                RpcResponse rpcResponse) throws Exception {
        logger.info("RpcStarter::Consumer:ReceiveResponse: get request result,{}", rpcResponse);
        this.response = rpcResponse;
        //TODO:请求有了结果，唤醒相应线程
        CompletableFuture future = requestFuture.get(ctx.channel().id());
        if (future != null)
            future.complete("");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("RpcStarter::Consumer 响应异常 Consumer caught exception,", cause);
        //TODO:异常将链接关闭
        InetSocketAddress inetSocketAddress = (InetSocketAddress)ctx.channel().remoteAddress();
        String key = inetSocketAddress.getHostName() + ":" + inetSocketAddress.getPort();
        channelMap.remove(key);
        channelGroup.remove(ctx.channel());
        requestFuture.remove(ctx.channel().id());
        ctx.close();
    }
}
