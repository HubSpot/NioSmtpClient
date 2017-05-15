package com.hubspot.smtp.client;

/**
 * A base exception that incorporates a connection ID into the exception message.
 *
 */
public abstract class SmtpException extends RuntimeException {
  public SmtpException(String connectionId, String message) {
    super(constructErrorMessage(connectionId, message));
  }

  private static String constructErrorMessage(String connectionId, String message) {
    if (connectionId == null || connectionId.length() == 0) {
      return message;
    } else {
      return String.format("[%s] %s", connectionId, message);
    }
  }
}
