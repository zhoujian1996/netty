package com.zj;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class test {

    public static void main(String[] args) {


        ChannelHandler channelHandler1 = new ChannelInboundHandlerAdapter(){
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                System.out.println("channerHandler1:read"+msg ); // channerHandler1:readPooledUnsafeDirectByteBuf(ridx: 0, widx: 10, cap: 256)::PooledUnsafeDirectByteBuf(ridx: 0, widx: 10, cap: 256)
                ByteBuf byteBuf =   (ByteBuf) msg;
                byte[] bytes = new byte[byteBuf.readableBytes()]; // 创建一个与ByteBuf大小相同的byte数组
                byteBuf.readBytes(bytes); // 读取ByteBuf中的数据到byte数组中
                String str = new String(bytes); // 将byte数组转换为字符串
                super.channelRead(ctx, str);
            }




            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channelHandler1:actve");
                super.channelActive(ctx);
            }
        };

        ChannelHandler channelHandler2 = new ChannelInboundHandlerAdapter(){
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                System.out.println("channerHandler2:read"+msg); // channerHandler2:readhelloworld
                super.channelRead(ctx, msg + "11");
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                System.out.println("channelHandler2:actve");
                super.channelActive(ctx);
            }
        };

        EmbeddedChannel embeddedChannel = new EmbeddedChannel(channelHandler1,channelHandler2);

        embeddedChannel.writeInbound(ByteBufAllocator.DEFAULT.buffer().writeBytes("helloworld".getBytes()));

//        embeddedChannel.writeInbound(123);

        Object o = embeddedChannel.readInbound();
        System.out.println("readINboud"+o); //readINboudhelloworld11
    }

}
