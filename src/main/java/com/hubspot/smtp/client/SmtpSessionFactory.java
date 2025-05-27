package com.hubspot.smtp.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates {@link SmtpSession} instances by connecting to remote servers.
 *
 * <p>This class is thread-safe.
 */
public class SmtpSessionFactory implements Closeable {

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
    ResponseHandler responseHandler = new ResponseHandler(
      config.getConnectionId(),
      config.getReadTimeout(),
      config.getExceptionHandler()
    );
    CompletableFuture<List<SmtpResponse>> initialResponseFuture =
      responseHandler.createResponseFuture(
        1,
        Optional.of(config.getInitialResponseTimeout()),
        () -> "initial response"
      );

    Bootstrap bootstrap = new Bootstrap()
      .group(factoryConfig.getEventLoopGroup())
      .channel(factoryConfig.getChannelClass())
      .option(ChannelOption.ALLOCATOR, factoryConfig.getAllocator())
      .option(
        ChannelOption.CONNECT_TIMEOUT_MILLIS,
        (int) config.getInitialResponseTimeout().toMillis()
      )
      .remoteAddress(config.getRemoteAddress())
      .localAddress(config.getLocalAddress().orElse(null))
      .handler(new Initializer(responseHandler, config));

    CompletableFuture<SmtpClientResponse> connectFuture = new CompletableFuture<>();

    bootstrap
      .connect()
      .addListener(f -> {
        if (f.isSuccess()) {
          Channel channel = ((ChannelFuture) f).channel();
          allChannels.add(channel);

          SmtpSession session = new SmtpSession(
            channel,
            responseHandler,
            config,
            factoryConfig.getExecutor(),
            factoryConfig.getSslEngineSupplier()
          );

          initialResponseFuture.handleAsync(
            (rs, e) -> {
              if (e != null) {
                session.close();
                connectFuture.completeExceptionally(e);
              } else {
                connectFuture.complete(new SmtpClientResponse(session, rs.get(0)));
              }

              return null;
            },
            factoryConfig.getExecutor()
          );
        } else {
          factoryConfig
            .getExecutor()
            .execute(() -> connectFuture.completeExceptionally(f.cause()));
        }
      });

    return connectFuture;
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
  public CompletableFuture<Void> closeAsync() {
    CompletableFuture<Void> returnedFuture = new CompletableFuture<>();

    allChannels
      .close()
      .addListener(f -> {
        if (f.isSuccess()) {
          returnedFuture.complete(null);
        } else {
          returnedFuture.completeExceptionally(f.cause());
        }
      });

    return returnedFuture;
  }
}
