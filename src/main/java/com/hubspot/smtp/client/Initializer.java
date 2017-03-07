package com.hubspot.smtp.client;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.smtp.SmtpRequestEncoder;
import io.netty.handler.codec.smtp.SmtpResponseDecoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;

class Initializer extends ChannelInitializer<SocketChannel> {
  private static final int MAX_LINE_LENGTH = 200;

  private final ResponseHandler responseHandler;
  private final SmtpSessionConfig config;

  Initializer(ResponseHandler responseHandler, SmtpSessionConfig config) {
    this.responseHandler = responseHandler;
    this.config = config;
  }

  @Override
  protected void initChannel(SocketChannel socketChannel) throws Exception {
    socketChannel.pipeline().addLast(
        new SmtpRequestEncoder(),
        new SmtpResponseDecoder(MAX_LINE_LENGTH),
        new ChunkedWriteHandler(),
        new ReadTimeoutHandler(config.getReadTimeoutSeconds()),
        responseHandler);
  }
}
