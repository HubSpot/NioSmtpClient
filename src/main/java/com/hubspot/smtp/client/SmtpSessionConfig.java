package com.hubspot.smtp.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

import org.immutables.value.Value.Check;
import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;

import com.google.common.base.Preconditions;

@Immutable
public abstract class SmtpSessionConfig {
  public abstract InetSocketAddress getRemoteAddress();
  public abstract Optional<InetSocketAddress> getLocalAddress();
  public abstract Optional<Duration> getKeepAliveTimeout();

  @Default
  public Duration getReadTimeout() {
    return Duration.ofMinutes(2);
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

  public static ImmutableSmtpSessionConfig forRemoteAddress(String host, int port) {
    return forRemoteAddress(InetSocketAddress.createUnresolved(host, port));
  }

  public static ImmutableSmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return ImmutableSmtpSessionConfig.builder().remoteAddress(remoteAddress).build();
  }
}
