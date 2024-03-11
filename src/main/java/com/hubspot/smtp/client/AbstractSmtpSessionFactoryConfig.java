package com.hubspot.smtp.client;

import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContextBuilder;
import java.security.KeyStore;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Style;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Shared configuration for all connections.
 */
@Immutable
@Style(typeImmutable = "*", visibility = ImplementationVisibility.PUBLIC)
abstract class AbstractSmtpSessionFactoryConfig {

  /**
   * A default {@code Executor} to use when executing {@code CompletableFuture} callbacks which simply executes
   * the callback in the netty event loop. This Executor should only be use when you can be sure your callback code never blocks.
   */
  public static final Executor DIRECT_EXECUTOR = Runnable::run;

  private static final com.google.common.base.Supplier<SmtpSessionFactoryConfig> NON_PRODUCTION_CONFIG =
    Suppliers.memoize(AbstractSmtpSessionFactoryConfig::createNonProductionConfig);

  private static SmtpSessionFactoryConfig createNonProductionConfig() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
      .setDaemon(true)
      .setNameFormat("niosmtpclient-%d")
      .build();

    return SmtpSessionFactoryConfig
      .builder()
      .eventLoopGroup(new NioEventLoopGroup(1, threadFactory))
      .executor(Executors.newCachedThreadPool(threadFactory))
      .build();
  }

  /**
   * Creates a configuration with a default {@code NioEventLoopGroup} and {@code Executor}. This is
   * NOT suitable for a production environment but is useful for testing.
   */
  public static SmtpSessionFactoryConfig nonProductionConfig() {
    return NON_PRODUCTION_CONFIG.get();
  }

  /**
   * An {@code Executor} that will be used to call {@code CompletableFuture} callbacks.
   */
  public abstract Executor getExecutor();

  /**
   * A Netty {@code EventLoopGroup} that will be used for all connections.
   */
  public abstract EventLoopGroup getEventLoopGroup();

  /**
   * A Netty {@code ByteBufAllocator} that will be used for all connections.
   */
  @Default
  public ByteBufAllocator getAllocator() {
    return PooledByteBufAllocator.DEFAULT;
  }

  /**
   * Creates an {@code SSLEngine} for use with STARTTLS connections.
   */
  @Default
  public Supplier<SSLEngine> getSslEngineSupplier() {
    return this::createSSLEngine;
  }

  /**
   * A Netty {@code Channel} implementation that will be used for all connections.
   */
  @Default
  public Class<? extends Channel> getChannelClass() {
    return NioSocketChannel.class;
  }

  private SSLEngine createSSLEngine() {
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      );
      trustManagerFactory.init((KeyStore) null);

      return SslContextBuilder
        .forClient()
        .trustManager(trustManagerFactory)
        .build()
        .newEngine(getAllocator());
    } catch (Exception e) {
      throw new RuntimeException("Could not create SSLEngine", e);
    }
  }
}
