package com.hubspot.smtp.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

import org.immutables.value.Value.Default;
import org.immutables.value.Value.Immutable;

@Immutable
public abstract class SmtpSessionConfig {
  public abstract InetSocketAddress getRemoteAddress();
  public abstract Optional<InetSocketAddress> getLocalAddress();

  @Default
  public Duration getReadTimeout() {
    return Duration.ofMinutes(2);
  }

  @Default
  public String getConnectionId() {
    return "unidentified-connection";
  }

  public static SmtpSessionConfig forRemoteAddress(String host, int port) {
    return forRemoteAddress(InetSocketAddress.createUnresolved(host, port));
  }

  public static SmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return ImmutableSmtpSessionConfig.builder().remoteAddress(remoteAddress).build();
  }
}
