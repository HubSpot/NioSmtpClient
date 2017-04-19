package com.hubspot.smtp.client;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.GlobalEventExecutor;

public class SmtpSessionFactory implements Closeable  {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSessionFactory.class);

  private final EventLoopGroup eventLoopGroup;
  private final ChannelGroup allChannels;

  public SmtpSessionFactory(EventLoopGroup eventLoopGroup) {
    this.eventLoopGroup = eventLoopGroup;

    allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  }

  public CompletableFuture<SmtpClientResponse> connect(SmtpSessionConfig config) {
    ResponseHandler responseHandler = new ResponseHandler(config.getConnectionId());
    CompletableFuture<SmtpResponse[]> initialResponseFuture = responseHandler.createResponseFuture(1, () -> "initial response");

    Bootstrap bootstrap = new Bootstrap()
        .group(eventLoopGroup)
        .channel(config.getChannelClass())
        .option(ChannelOption.ALLOCATOR, config.getAllocator())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) getMillis(config.getConnectionTimeout()))
        .remoteAddress(config.getRemoteAddress())
        .localAddress(config.getLocalAddress().orElse(null))
        .handler(new Initializer(responseHandler, config));

    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();

    bootstrap.connect().addListener(f -> {
      if (f.isSuccess()) {
        Channel channel = ((ChannelFuture) f).channel();
        allChannels.add(channel);

        SmtpSession session = new SmtpSession(channel, responseHandler, config);
        applyOnExecutor(config, initialResponseFuture, r -> connectFuture.complete(new SmtpClientResponse(session, r[0])));
      } else {
        LOG.error("Could not connect to {}", config.getRemoteAddress(), f.cause());
        config.getEffectiveExecutor().execute(() -> connectFuture.completeExceptionally(f.cause()));
      }
    });

    return connectFuture;
  }

  private long getMillis(Duration duration) {
    return TimeUnit.NANOSECONDS.convert(duration.getNano(), TimeUnit.MILLISECONDS);
  }

  private <R, T> void applyOnExecutor(SmtpSessionConfig config, CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw new RuntimeException(e);
      }

      return mapper.apply(rs);
    }, config.getEffectiveExecutor());
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
