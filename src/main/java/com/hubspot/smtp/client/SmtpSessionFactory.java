package com.hubspot.smtp.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.GlobalEventExecutor;

public class SmtpSessionFactory implements Closeable  {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSessionFactory.class);

  private final NioEventLoopGroup eventLoopGroup;
  private final ExecutorService executorService;
  private final ChannelGroup allChannels;

  public SmtpSessionFactory(NioEventLoopGroup eventLoopGroup, ExecutorService executorService) {
    this.eventLoopGroup = eventLoopGroup;
    this.executorService = executorService;

    allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  }

  public CompletableFuture<SmtpClientResponse> connect(SmtpSessionConfig config) {
    ResponseHandler responseHandler = new ResponseHandler(config.getConnectionId());
    CompletableFuture<SmtpResponse[]> initialResponseFuture = responseHandler.createResponseFuture(1, () -> "initial response");

    Bootstrap bootstrap = new Bootstrap()
        .group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.ALLOCATOR, config.getAllocator())
        .remoteAddress(config.getRemoteAddress())
        .localAddress(config.getLocalAddress().orElse(null))
        .handler(new Initializer(responseHandler, config));

    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();

    bootstrap.connect().addListener(f -> {
      if (f.isSuccess()) {
        Channel channel = ((ChannelFuture) f).channel();
        allChannels.add(channel);

        SmtpSession session = new SmtpSession(channel, responseHandler, executorService, config);
        applyOnExecutor(initialResponseFuture, r -> connectFuture.complete(new SmtpClientResponse(r[0], session)));
      } else {
        LOG.error("Could not connect to {}", config.getRemoteAddress(), f.cause());
        executorService.execute(() -> connectFuture.completeExceptionally(f.cause()));
      }
    });

    return connectFuture;
  }

  private <R, T> CompletableFuture<R> applyOnExecutor(CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    return eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw new RuntimeException(e);
      }

      return mapper.apply(rs);
    }, executorService);
  }

  @Override
  public void close() throws IOException {
    try {
      allChannels.close().await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
  public CompletableFuture<Void> closeAsync() {
    CompletableFuture<Void> returnedFuture = new CompletableFuture<>();

    allChannels.close().addListener(f -> {
      if (f.isSuccess()) {
        returnedFuture.complete(null);
      } else {
        returnedFuture.completeExceptionally(f.cause());
      }
    });

    return returnedFuture;
  }

}
