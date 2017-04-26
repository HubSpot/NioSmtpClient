package com.hubspot.smtp.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;

import org.immutables.value.Value.Check;
import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Style;
import org.immutables.value.Value.Style.ImplementationVisibility;

import com.google.common.base.Preconditions;

@Immutable
@Style(typeImmutable = "*", visibility = ImplementationVisibility.PUBLIC)
abstract class AbstractSmtpSessionConfig {
  public abstract InetSocketAddress getRemoteAddress();
  public abstract Optional<InetSocketAddress> getLocalAddress();
  public abstract Optional<Duration> getKeepAliveTimeout();
  public abstract Optional<SendInterceptor> getSendInterceptor();

  @Default
  public Duration getConnectionTimeout() {
    return Duration.ofMinutes(2);
  }

  @Default
  public Duration getReadTimeout() {
    return Duration.ofMinutes(2);
  }

  @Default
  public EnumSet<Extension> getDisabledExtensions() {
    return EnumSet.noneOf(Extension.class);
  }

  @Default
  public String getConnectionId() {
    return "unidentified-connection";
  }

  @Check
  protected void check() {
    Preconditions.checkState(!getKeepAliveTimeout().orElse(Duration.ofSeconds(1)).isZero(),
        "keepAliveTimeout must not be zero; use Optional.empty() to disable keepalive");
  }

  public static SmtpSessionConfig forRemoteAddress(String host, int port) {
    return forRemoteAddress(InetSocketAddress.createUnresolved(host, port));
  }

  public static SmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return SmtpSessionConfig.builder().remoteAddress(remoteAddress).build();
  }
}
