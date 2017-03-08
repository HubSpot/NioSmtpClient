package com.hubspot.smtp.client;

public class ErrorResponseException extends SmtpException {
  public ErrorResponseException(String connectionId, String message) {
    super(connectionId, message);
  }
}
