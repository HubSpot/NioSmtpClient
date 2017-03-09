package com.hubspot.smtp.client;

import com.hubspot.smtp.utils.SmtpResponses;

import io.netty.handler.codec.smtp.SmtpResponse;

public class ErrorResponseException extends SmtpException {
  public ErrorResponseException(String connectionId, SmtpResponse response, String message) {
    super(connectionId, String.format("%s (%s)", message, SmtpResponses.toString(response)));
  }
}
