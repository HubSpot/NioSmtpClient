package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

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
  private static final SmtpRequest MAIL_REQUEST = new DefaultSmtpRequest(SmtpCommand.MAIL, "FROM:alice@example.com");
  private static final SmtpRequest RCPT_REQUEST = new DefaultSmtpRequest(SmtpCommand.RCPT, "FROM:bob@example.com");
  private static final SmtpRequest DATA_REQUEST = new DefaultSmtpRequest(SmtpCommand.DATA);
  private static final SmtpRequest EHLO_REQUEST = new DefaultSmtpRequest(SmtpCommand.EHLO);
  private static final SmtpRequest NOOP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpRequest HELO_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELO);
  private static final SmtpRequest HELP_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELP);

  private static final ExecutorService EXECUTOR_SERVICE = MoreExecutors.sameThreadExecutor();

  private ResponseHandler responseHandler;
  private CompletableFuture<SmtpResponse[]> responseFuture;
  private Channel channel;
  private SmtpSession session;

  @Before
  public void setup() {
    channel = mock(Channel.class);
    responseHandler = mock(ResponseHandler.class);
    session = new SmtpSession(channel, responseHandler, EXECUTOR_SERVICE);

    responseFuture = new CompletableFuture<>();
    when(responseHandler.createResponseFuture(anyInt(), any())).thenReturn(responseFuture);
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
  public void itWrapsTheResponse() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);

    responseFuture.complete(new SmtpResponse[] { SMTP_RESPONSE });

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().code()).isEqualTo(SMTP_RESPONSE.code());
    assertThat(future.get().details()).isEqualTo(SMTP_RESPONSE.details());
  }

  @Test
  public void itExecutesReturnedFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, executorService);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Void> assertionFuture = future.thenRun(() -> assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor"));
    responseFuture.complete(new SmtpResponse[] { SMTP_RESPONSE });

    assertionFuture.join();
  }

  @Test
  public void itSendsPipelinedRequests() {
    List<SmtpContent> contents = Lists.newArrayList(SMTP_CONTENT, LAST_SMTP_CONTENT);
    session.sendPipelined(contents, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(SMTP_CONTENT);
    order.verify(channel).write(LAST_SMTP_CONTENT);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).write(RCPT_REQUEST);
    order.verify(channel).write(DATA_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itCanSendASingleCommandWithPipelined() {
    // this is the same as just calling send
    session.sendPipelined(MAIL_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itChecksPipelineArgumentsAreValid() {
    assertPipelineError("DATA must appear last in a pipelined request", DATA_REQUEST, MAIL_REQUEST);
    assertPipelineError("EHLO must appear last in a pipelined request", EHLO_REQUEST, MAIL_REQUEST);
    assertPipelineError("NOOP must appear last in a pipelined request", NOOP_REQUEST, MAIL_REQUEST);

    assertPipelineError("HELO cannot be used in a pipelined request", HELO_REQUEST);
    assertPipelineError("HELP cannot be used in a pipelined request", HELP_REQUEST);
  }

  private void assertPipelineError(String message, SmtpRequest... requests) {
    assertThatThrownBy(() -> session.sendPipelined(requests))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  @Test
  public void itWrapsTheResponsesWhenPipelining() throws ExecutionException, InterruptedException {
    List<SmtpContent> contents = Lists.newArrayList(SMTP_CONTENT, LAST_SMTP_CONTENT);
    CompletableFuture<SmtpClientResponse[]> future = session.sendPipelined(contents, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    SmtpResponse[] responses = {SMTP_RESPONSE, SMTP_RESPONSE, SMTP_RESPONSE, SMTP_RESPONSE};
    responseFuture.complete(responses);

    // 4 responses expected: one for the content, 3 for the requests
    verify(responseHandler).createResponseFuture(eq(4), any());

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().length).isEqualTo(responses.length);
    assertThat(future.get()[0].getSession()).isEqualTo(session);
    assertThat(future.get()[0].code()).isEqualTo(SMTP_RESPONSE.code());
  }

  @Test
  public void itExpectsTheRightNumberOfResponsesWhenPipelining() {
    session.sendPipelined(RCPT_REQUEST, DATA_REQUEST);

    // 1 response expected for each request
    verify(responseHandler).createResponseFuture(eq(2), any());
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
