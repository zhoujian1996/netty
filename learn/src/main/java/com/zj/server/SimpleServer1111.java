package com.zj.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class SimpleServer1111 {

    public static void main(String[] args) throws InterruptedException {


        // EventLoopGroup：
        // 服务端的线程模型外观类。 从字面意思可以了 解到， Netty的线 程模型是事件驱动型的，
        // 也就是说， 这个线程要做的事情就是不停地检测IO事件、 处理IO事件、 执行任务， 不断重复这三个步骤
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // ServerBootstrap： 服务端的一个启动辅助类。 通过给它设置一系列参数来绑定端口启动服务
            ServerBootstrap b = new ServerBootstrap();
            //  设置服务端的线程模型。
            //  读者可以先想象一下： 在一个工厂里， 我们需要两种类型的人干活， 一种是老板， 一种是工人。
            //  老板负责从外面接活， 把接到的活分配给工人。
            //  放到这里， bossGroup的作用就是不断地接收新的连接， 将新连接交给workerGroup来处理。
            b.group(bossGroup, workerGroup) // 服务器端创建bossGroup与workerGroup
                    // 设置服务员的IO类型为NIO。 Netty通过指定Channel的类型来指定IO类型。
                    // Channel在Netty里是一大核心概念， 可以理解为， 一个Channel就是一个连接或者一个服务
                    // 端bind动作
                    .channel(NioServerSocketChannel.class) // 调用默认构造起创建一个NioServerSocket对象
                    // 表示在服务端启动过程中，需要经过哪些流程。 这里SimpleServerHandler最终的顶层接口为
                    //ChannelHandler， 是Netty的一大核心概念， 表示数据流经过的处理器，
                    //可以理解为流水线上的每一道关卡
                    .handler(new SimpleServerHandler())
                    //   使用过Netty的读者应该知道， 这里的方法体主要用于设置一系列Handler来处
                    //理每个连接的数据， 也就是上面所说的， 老板接到一个活之后， 告诉每
                    //个工人这个活的固定步骤
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {

                        }
                    });
            // 绑定端口同步等待。 这里就是真正的启动过程了， 绑定端口8888， 等服务端启动完毕， 才会进入下一行代码。
            ChannelFuture f = b.bind(8888).sync();
            //  等待服务端关闭端口绑定， 这里的作用其实是让程序不会退出
            f.channel().closeFuture().sync();


        } finally {
            // 关闭两组事件循环， 关闭之后 main方法就结束了
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
