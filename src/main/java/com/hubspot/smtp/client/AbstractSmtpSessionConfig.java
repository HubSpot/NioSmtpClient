package com.hubspot.smtp.client;

import com.google.common.base.Preconditions;
import io.netty.channel.ChannelHandler;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Consumer;
import org.immutables.value.Value.Check;
import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Style;
import org.immutables.value.Value.Style.ImplementationVisibility;

/**
 * Configures a connection to a remote SMTP server.
 */
@Immutable
@Style(typeImmutable = "*", visibility = ImplementationVisibility.PUBLIC)
abstract class AbstractSmtpSessionConfig {

  /**
   * The host and port of the remote server.
   */
  public abstract InetSocketAddress getRemoteAddress();

  /**
   * The local address and port to use when connecting to the remote server.
   */
  public abstract Optional<InetSocketAddress> getLocalAddress();

  /**
   * The time to wait before sending a NOOP command to keep an
   * otherwise idle connection alive.
   */
  public abstract Optional<Duration> getKeepAliveTimeout();

  /**
   * The time to wait for a response from the server.
   */
  public abstract Optional<Duration> getReadTimeout();

  /**
   * The time to wait for the initial response from the server.
   *
   * @deprecated use getInitialResponseTimeout instead, which applies to the combined time of connecting and receiving the initial response
   */
  @Deprecated
  public abstract Optional<Duration> getInitialResponseReadTimeout();

  /**
   * The time to wait for the initial response from the server. This includes the connect time.
   */
  @Default
  public Duration getInitialResponseTimeout() {
    return getInitialResponseReadTimeout().orElse(Duration.ofMinutes(1));
  }

  /**
   * A {@link SendInterceptor} that can intercept commands and data before
   * they are sent to the server.
   *
   * <p>{@code SendInterceptor}s are especially useful when using the
   * {@code SmtpSession#send} overloads that send multiple commands
   * for you. If you provide a {@code SendInterceptor} to the {@code send}
   * method, it will take precedence over this one.
   */
  public abstract Optional<SendInterceptor> getSendInterceptor();

  /**
   * A handler for exceptions that happen outside sending individual messages.
   */
  public abstract Optional<Consumer<Throwable>> getExceptionHandler();

  /**
   * The time to wait while connecting to a remote server.
   *
   * @deprecated use initialResponseTimeout instead, which applies to the combined time of connecting and receiving the initial response
   */
  @Default
  @Deprecated
  public Duration getConnectionTimeout() {
    return Duration.ofMinutes(2);
  }

  /**
   * Extensions which should not be used when communicating with the remote server.
   *
   * <p>This is useful if a server's EHLO response indicates supports for a particular extension
   * but the implementation isn't working.
   */
  @Default
  public EnumSet<Extension> getDisabledExtensions() {
    return EnumSet.noneOf(Extension.class);
  }

  /**
   * An opaque string that will be logged with any errors on this connection.
   */
  @Default
  public String getConnectionId() {
    return "unidentified-connection";
  }

  @Default
  public ChannelHandler[] getAddFirstCustomHandlers() {
    return new ChannelHandler[] {};
  }

  @Check
  protected void check() {
    Preconditions.checkState(
      !getKeepAliveTimeout().orElse(Duration.ofSeconds(1)).isZero(),
      "keepAliveTimeout must not be zero; use Optional.empty() to disable keepalive"
    );
  }

  /**
   * Creates a configuration for a connection to a server on the specified host and port.
   */
  public static SmtpSessionConfig forRemoteAddress(String host, int port) {
    return forRemoteAddress(InetSocketAddress.createUnresolved(host, port));
  }

  /**
   * Creates a configuration for a connection to a server on the specified host and port.
   */
  public static SmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return SmtpSessionConfig.builder().remoteAddress(remoteAddress).build();
  }
}
