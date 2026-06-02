package com.hubspot.smtp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.apache.james.protocols.api.ProtocolSession;
import org.apache.james.protocols.api.ProtocolSession.AttachmentKey;
import org.apache.james.protocols.api.ProtocolSession.State;
import org.apache.james.protocols.netty.AllButStartTlsLineChannelHandlerFactory;
import org.apache.james.protocols.netty.BasicChannelInboundHandler;

public class ChunkingChannelHandlerFactory
  extends AllButStartTlsLineChannelHandlerFactory {

  static final AttachmentKey<Channel> NETTY_CHANNEL = AttachmentKey.of(
    "netty channel",
    Channel.class
  );

  private static final String SESSION_CHANNEL_STASHER = "sessionChannelStasher";

  public ChunkingChannelHandlerFactory(String pattern, int maxFrameLength) {
    super(pattern, maxFrameLength);
  }

  @Override
  public ChannelHandler create(ChannelPipeline pipeline) {
    // James creates the SMTPSession in its core handler and stores it on the channel.
    // Chunking needs the reverse mapping (channel from session) so it can splice a
    // byte-capturing handler into the pipeline when a BDAT command arrives, so this
    // handler stashes the channel onto the session as soon as the session exists.
    pipeline.addLast(SESSION_CHANNEL_STASHER, new SessionChannelStashingHandler());
    return super.create(pipeline);
  }

  private static class SessionChannelStashingHandler
    extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      Channel channel = ctx.channel();
      Object session = channel
        .attr(BasicChannelInboundHandler.SESSION_ATTRIBUTE_KEY)
        .get();

      if (session instanceof ProtocolSession) {
        ProtocolSession protocolSession = (ProtocolSession) session;
        if (protocolSession.getAttachment(NETTY_CHANNEL, State.Connection).isEmpty()) {
          protocolSession.setAttachment(NETTY_CHANNEL, channel, State.Connection);
        }
      }

      ctx.fireChannelRead(msg);
    }
  }
}
