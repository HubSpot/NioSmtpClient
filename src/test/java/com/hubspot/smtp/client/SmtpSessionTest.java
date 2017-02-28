package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.smtp.DefaultLastSmtpContent;
import io.netty.handler.codec.smtp.DefaultSmtpContent;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class SmtpSessionTest {
  private static final SmtpRequest SMTP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpContent SMTP_CONTENT = new DefaultSmtpContent(Unpooled.copiedBuffer(new byte[1]));
  private static final SmtpContent LAST_SMTP_CONTENT = new DefaultLastSmtpContent(Unpooled.copiedBuffer(new byte[2]));
  private static final SmtpResponse SMTP_RESPONSE = new DefaultSmtpResponse(250, "OK");

  private ResponseHandler responseHandler;
  private CompletableFuture<SmtpResponse> responseFuture;
  private Channel channel;
  private SmtpSession session;

  @Before
  public void setup() {
    channel = mock(Channel.class);
    responseHandler = mock(ResponseHandler.class);
    session = new SmtpSession(channel, responseHandler);

    responseFuture = new CompletableFuture<>();
    when(responseHandler.createResponseFuture()).thenReturn(responseFuture);
  }
  
  @Test
  public void itSendsRequests() {
    session.send(SMTP_REQUEST);

    verify(channel).writeAndFlush(SMTP_REQUEST);
  }

  @Test
  public void itSendsContents() {
    session.send(SMTP_CONTENT, LAST_SMTP_CONTENT);

    verify(channel).write(SMTP_CONTENT);
    verify(channel).write(LAST_SMTP_CONTENT);
    verify(channel).flush();
  }

  @Test
  public void itWrapsTheResponseResult() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);

    responseFuture.complete(SMTP_RESPONSE);

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().code()).isEqualTo(SMTP_RESPONSE.code());
    assertThat(future.get().details()).isEqualTo(SMTP_RESPONSE.details());
  }

  @Test
  public void itRecordsSupportedExtensions() {
    session.setSupportedExtensions(EnumSet.of(SupportedExtensions.EIGHT_BIT_MIME));

    assertThat(session.isSupported(SupportedExtensions.EIGHT_BIT_MIME)).isTrue();
    assertThat(session.isSupported(SupportedExtensions.PIPELINING)).isFalse();
  }

  @Test
  public void itClosesTheUnderlyingChannel() {
    DefaultChannelPromise channelPromise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    when(channel.close()).thenReturn(channelPromise);

    CompletableFuture<Void> f = session.close();
    channelPromise.setSuccess();

    assertThat(f.isDone());
  }
}
