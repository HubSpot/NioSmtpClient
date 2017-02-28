package com.hubspot.smtp.client;

import java.net.InetSocketAddress;

public class SmtpSessionConfig {
  private final InetSocketAddress remoteAddress;
  private final InetSocketAddress localAddress;

  public static SmtpSessionConfig forRemoteAddress(InetSocketAddress remoteAddress) {
    return new SmtpSessionConfig(remoteAddress, null);
  }

  public SmtpSessionConfig(InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
    this.remoteAddress = remoteAddress;
    this.localAddress = localAddress;
  }

  public InetSocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  public InetSocketAddress getLocalAddress() {
    return localAddress;
  }
}
