package com.hubspot.smtp.client;

public class ChannelClosedException extends SmtpException {
  public ChannelClosedException(String connectionId, String message) {
    super(connectionId, message);
  }
}
