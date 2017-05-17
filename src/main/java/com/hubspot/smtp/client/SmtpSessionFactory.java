package com.hubspot.smtp.client;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Creates {@link SmtpSession} instances by connecting to remote servers.
 *
 * <p>This class is thread-safe.
 */
public class SmtpSessionFactory implements Closeable  {
  private static final Logger LOG = LoggerFactory.getLogger(SmtpSessionFactory.class);

  private final ChannelGroup allChannels;
  private final SmtpSessionFactoryConfig factoryConfig;

  /**
   * Creates a new factory with the provided configuration.
   */
  public SmtpSessionFactory(SmtpSessionFactoryConfig factoryConfig) {
    this.factoryConfig = factoryConfig;

    allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
  }

  /**
   * Connects to a remote server.
   *
   * @param  config the configuration to use for the connection
   * @return a future representing the initial response from the server
   */
  public CompletableFuture<SmtpClientResponse> connect(SmtpSessionConfig config) {
    ResponseHandler responseHandler = new ResponseHandler(config.getConnectionId(), config.getReadTimeout(), config.getExceptionHandler());
    CompletableFuture<List<SmtpResponse>> initialResponseFuture = responseHandler.createResponseFuture(1, () -> "initial response");

    Bootstrap bootstrap = new Bootstrap()
        .group(factoryConfig.getEventLoopGroup())
        .channel(factoryConfig.getChannelClass())
        .option(ChannelOption.ALLOCATOR, factoryConfig.getAllocator())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectionTimeout().toMillis())
        .remoteAddress(config.getRemoteAddress())
        .localAddress(config.getLocalAddress().orElse(null))
        .handler(new Initializer(responseHandler, config));

    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();

    bootstrap.connect().addListener(f -> {
      if (f.isSuccess()) {
        Channel channel = ((ChannelFuture) f).channel();
        allChannels.add(channel);

        SmtpSession session = new SmtpSession(channel, responseHandler, config, factoryConfig.getExecutor(), factoryConfig.getSslEngineSupplier());
        applyOnExecutor(config, initialResponseFuture, r -> connectFuture.complete(new SmtpClientResponse(session, r.get(0))));
      } else {
        factoryConfig.getExecutor().execute(() -> connectFuture.completeExceptionally(f.cause()));
      }
    });

    return connectFuture;
  }

  private <R, T> void applyOnExecutor(SmtpSessionConfig config, CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw new RuntimeException(e);
      }

      return mapper.apply(rs);
    }, factoryConfig.getExecutor());
  }

  @Override
  public void close() throws IOException {
    try {
      allChannels.close().await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes all sessions created by this factory.
   *
   * @return a future that will be completed when all sessions have been closed
   */
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
