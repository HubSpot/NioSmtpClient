package com.hubspot.smtp.client;

public class ChannelClosedException extends Exception {
  public ChannelClosedException(String connectionId, String message) {
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
