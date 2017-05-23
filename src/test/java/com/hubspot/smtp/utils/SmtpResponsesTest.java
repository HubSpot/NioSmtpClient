package com.hubspot.smtp.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.collect.Lists;

import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponse;

public class SmtpResponsesTest {
  private static final SmtpResponse OK_RESPONSE = new DefaultSmtpResponse(250, "ARG1", "ARG2");
  private static final SmtpResponse OK_NO_DETAILS_RESPONSE = new DefaultSmtpResponse(250);
  private static final SmtpResponse NO_DETAILS_RESPONSE = new DefaultSmtpResponse(250);
  private static final SmtpResponse EHLO_RESPONSE = new DefaultSmtpResponse(250, "PIPELINING", "CHUNKING");
  private static final SmtpResponse TRANSIENT_ERROR_RESPONSE = new DefaultSmtpResponse(400);
  private static final SmtpResponse PERMANENT_ERROR_RESPONSE = new DefaultSmtpResponse(500);

  @Test
  public void itFormatsResponsesAsAString() {
    assertThat(SmtpResponses.toString(OK_RESPONSE)).isEqualTo("250 ARG1 ARG2");
    assertThat(SmtpResponses.toString(OK_NO_DETAILS_RESPONSE)).isEqualTo("250");
  }

  @Test
  public void itIgnoresNullDetails() {
    assertThat(SmtpResponses.toString(new DefaultSmtpResponse(250, null, "ARG2"))).isEqualTo("250 ARG2");
  }

  @Test
  public void itFormatsResponsesAsALines() {
    assertThat(SmtpResponses.getLines(EHLO_RESPONSE)).isEqualTo(Lists.newArrayList("250-PIPELINING", "250 CHUNKING"));
  }

  @Test
  public void itFormatsResponsesWithoutDetailsAsASingleLine() {
    assertThat(SmtpResponses.getLines(NO_DETAILS_RESPONSE)).isEqualTo(Lists.newArrayList("250"));
  }

  @Test
  public void itCanDetectTransientErrors() {
    assertThat(SmtpResponses.isTransientError(OK_RESPONSE)).isFalse();
    assertThat(SmtpResponses.isTransientError(PERMANENT_ERROR_RESPONSE)).isFalse();
    assertThat(SmtpResponses.isTransientError(TRANSIENT_ERROR_RESPONSE)).isTrue();
  }

  @Test
  public void itCanDetectPermanenetErrors() {
    assertThat(SmtpResponses.isPermanentError(OK_RESPONSE)).isFalse();
    assertThat(SmtpResponses.isPermanentError(TRANSIENT_ERROR_RESPONSE)).isFalse();
    assertThat(SmtpResponses.isPermanentError(PERMANENT_ERROR_RESPONSE)).isTrue();
  }

  @Test
  public void itCanDetectErrors() {
    assertThat(SmtpResponses.isError(OK_RESPONSE)).isFalse();
    assertThat(SmtpResponses.isError(TRANSIENT_ERROR_RESPONSE)).isTrue();
    assertThat(SmtpResponses.isError(PERMANENT_ERROR_RESPONSE)).isTrue();
  }
}
