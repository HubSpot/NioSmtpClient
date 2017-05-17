package com.hubspot.smtp.client;

import com.hubspot.smtp.utils.SmtpResponses;

import io.netty.handler.codec.smtp.SmtpResponse;

/**
 * Unchecked exception thrown when an error was received in response to a NOOP command.
 *
 */
public class NoopErrorResponseException extends SmtpException {
  public NoopErrorResponseException(String connectionId, SmtpResponse response, String message) {
    super(connectionId, String.format("%s (%s)", message, SmtpResponses.toString(response)));
  }
}
