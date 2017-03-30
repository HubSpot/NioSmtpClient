package com.hubspot.smtp;

import org.apache.james.protocols.api.Encryption;
import org.apache.james.protocols.api.Protocol;
import org.apache.james.protocols.api.ProtocolSession;
import org.apache.james.protocols.api.ProtocolSession.State;
import org.apache.james.protocols.netty.BasicChannelUpstreamHandler;
import org.apache.james.protocols.netty.NettyServer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelUpstreamHandler;

public class ExtensibleNettyServer extends NettyServer {
  static final String NETTY_CHANNEL = "netty channel";

  public ExtensibleNettyServer(Protocol protocol, Encryption secure) {
    super(protocol, secure);
  }

  protected ChannelUpstreamHandler createCoreHandler() {
    // Supporting chunking is difficult because James offers a line-oriented interface.
    // By saving the Netty channel into James' SMTPSession, we can add a Netty handler
    // that can intercept the BDAT message body before James attempts to parse it
    // into a series of lines.
    return new BasicChannelUpstreamHandler(protocol, secure) {
      @Override
      protected ProtocolSession createSession(ChannelHandlerContext ctx) throws Exception {
        ProtocolSession session = super.createSession(ctx);
        session.setAttachment(NETTY_CHANNEL, ctx.getChannel(), State.Connection);
        return session;
      }
    };
  }
}
