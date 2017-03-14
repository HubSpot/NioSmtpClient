package com.hubspot.smtp.client;

public class MessageTooLargeException extends SmtpException {
  public MessageTooLargeException(String connectionId, long maxMessageSize) {
    super(connectionId, String.format("This message is too large to be sent (max size: %d)", maxMessageSize));
  }
}
