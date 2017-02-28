package com.hubspot.smtp.client;

import java.util.List;

import com.google.common.base.Joiner;

import io.netty.handler.codec.smtp.SmtpResponse;

public class SmtpClientResponse implements SmtpResponse {
  private static final Joiner SPACE_JOINER = Joiner.on(" ");

  private final SmtpResponse response;
  private final SmtpSession session;

  public SmtpClientResponse(SmtpResponse response, SmtpSession session) {
    this.response = response;
    this.session = session;
  }

  @Override
  public int code() {
    return response.code();
  }

  @Override
  public List<CharSequence> details() {
    return response.details();
  }

  public SmtpSession getSession() {
    return session;
  }

  @Override
  public String toString() {
    if (response == null) {
      return super.toString();
    } else {
      return code() + " " + SPACE_JOINER.join(details());
    }
  }
}
