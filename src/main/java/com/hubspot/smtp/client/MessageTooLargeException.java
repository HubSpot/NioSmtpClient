package com.hubspot.smtp.client;

/**
 * Unchecked exception thrown when the provided content is too large to be sent according to
 * the server's EHLO response.
 *
 */
public class MessageTooLargeException extends SmtpException {

  public MessageTooLargeException(String connectionId, long maxMessageSize) {
    super(
      connectionId,
      String.format("This message is too large to be sent (max size: %d)", maxMessageSize)
    );
  }
}
