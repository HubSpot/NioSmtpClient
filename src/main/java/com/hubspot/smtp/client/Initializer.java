package com.hubspot.smtp.client;

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.smtp.SmtpRequestEncoder;
import io.netty.handler.codec.smtp.SmtpResponseDecoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;

class Initializer extends ChannelInitializer<SocketChannel> {
  private static final int MAX_LINE_LENGTH = 1000;

  private final ResponseHandler responseHandler;
  private final SmtpSessionConfig config;

  Initializer(ResponseHandler responseHandler, SmtpSessionConfig config) {
    this.responseHandler = responseHandler;
    this.config = config;
  }

  @Override
  protected void initChannel(SocketChannel socketChannel) throws Exception {
    socketChannel.pipeline().addLast(getChannelHandlers());
  }

  private ChannelHandler[] getChannelHandlers() {
    List<ChannelHandler> handlers = new ArrayList<>();

    handlers.add(new SmtpRequestEncoder());
    handlers.add(new SmtpResponseDecoder(MAX_LINE_LENGTH));
    handlers.add(new ChunkedWriteHandler());
    handlers.add(new ReadTimeoutHandler(Math.toIntExact(config.getReadTimeout().getSeconds())));

    config.getKeepAliveTimeout().ifPresent(timeout -> handlers.add(new KeepAliveHandler(responseHandler, config.getConnectionId(), timeout)));

    handlers.add(responseHandler);

    return handlers.toArray(new ChannelHandler[handlers.size()]);
  }
}
