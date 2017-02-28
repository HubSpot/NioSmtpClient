package com.hubspot.smtp.client;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.smtp.SmtpRequestEncoder;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponseDecoder;

public class SmtpSessionFactory {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSessionFactory.class);

  private static final int MAX_LINE_LENGTH = 200;

  public CompletableFuture<SmtpClientResponse> connect(NioEventLoopGroup group, SmtpSessionConfig config) {
    ResponseHandler responseHandler = new ResponseHandler();
    CompletableFuture<SmtpResponse[]> initialResponseFuture = responseHandler.createResponseFuture(1);

    Bootstrap bootstrap = new Bootstrap()
        .group(group)
        .channel(NioSocketChannel.class)
        .remoteAddress(config.getRemoteAddress())
        .localAddress(config.getLocalAddress())
        .handler(new Initializer(responseHandler));

    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();

    bootstrap.connect().addListener(f -> {
      if (f.isSuccess()) {
        SmtpSession session = new SmtpSession(((ChannelFuture) f).channel(), responseHandler);
        initialResponseFuture.thenAccept(r -> connectFuture.complete(new SmtpClientResponse(r[0], session)));
      } else {
        LOG.error("Could not connect to {}", config.getRemoteAddress(), f.cause());
        connectFuture.completeExceptionally(f.cause());
      }
    });

    return connectFuture;
  }

  private static class Initializer extends ChannelInitializer<SocketChannel> {
    private final ResponseHandler responseHandler;

    public Initializer(ResponseHandler responseHandler) {
      this.responseHandler = responseHandler;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
      socketChannel.pipeline().addLast(
          new SmtpRequestEncoder(),
          new SmtpResponseDecoder(MAX_LINE_LENGTH),
          responseHandler);
    }
  }
}
