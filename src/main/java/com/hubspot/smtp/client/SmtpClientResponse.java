package com.hubspot.smtp.client;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.hubspot.smtp.utils.SmtpResponses;

import io.netty.handler.codec.smtp.SmtpResponse;

/**
 * Wraps the session and the responses to one or more SMTP commands.
 *
 * <p>This class is thread-safe.
 */
public class SmtpClientResponse  {
  private final SmtpSession session;
  private final List<SmtpResponse> responses;

  public SmtpClientResponse(SmtpSession session, SmtpResponse response) {
    this.responses = ImmutableList.of(response);
    this.session = session;
  }

  public SmtpClientResponse(SmtpSession session, Iterable<SmtpResponse> responses) {
    this.responses = ImmutableList.copyOf(responses);
    this.session = session;
  }

  public SmtpClientResponse(SmtpSession session, List<SmtpResponse> responses) {
    this.responses = ImmutableList.copyOf(responses);
    this.session = session;
  }

  /**
   * Gets the {@link SmtpSession} that received these responses.
   */
  public SmtpSession getSession() {
    return session;
  }

  /**
   * Gets whether any of the contained {@link SmtpResponse}s have a {@code code >= 400}.
   */
  public boolean containsError() {
    return responses.stream().anyMatch(SmtpResponses::isError);
  }

  /**
   * Gets the {@link SmtpResponse}s.
   */
  public List<SmtpResponse> getResponses() {
    return responses;
  }

  @Override
  public String toString() {
    return responses.stream().map(SmtpResponses::toString).collect(Collectors.joining("; "));
  }
}
