package com.hubspot.smtp.client;

import java.net.InetSocketAddress;

public class SmtpSessionConfig {
  private final InetSocketAddress remoteAddress;
  private final InetSocketAddress localAddress;

  private int readTimeoutSeconds = 30;
  private String connectionId = "unidentified-connection";

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

  public int getReadTimeoutSeconds() {
    return readTimeoutSeconds;
  }

  public SmtpSessionConfig setReadTimeoutSeconds(int readTimeoutSeconds) {
    this.readTimeoutSeconds = readTimeoutSeconds;
    return this;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public SmtpSessionConfig setConnectionId(String connectionId) {
    this.connectionId = connectionId;
    return this;
  }
}
