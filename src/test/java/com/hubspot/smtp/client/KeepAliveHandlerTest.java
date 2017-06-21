package com.hubspot.smtp.client;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.timeout.IdleStateEvent;

public class KeepAliveHandlerTest {
  private static final String CONNECTION_ID = "connection";
  private static final SmtpResponse OK_RESPONSE = new DefaultSmtpResponse(250);
  private static final SmtpResponse ERROR_RESPONSE = new DefaultSmtpResponse(400, "that didn't work");

  private ChannelHandlerContext context;
  private Channel channel;
  private ResponseHandler responseHandler;
  private TestHandler handler;

  @Before
  public void setup() {
    context = mock(ChannelHandlerContext.class);
    channel = mock(Channel.class);
    responseHandler = mock(ResponseHandler.class);
    handler = new TestHandler(responseHandler, CONNECTION_ID, Duration.ofSeconds(30));

    when(context.channel()).thenReturn(channel);
    when(responseHandler.getPendingResponseDebugString()).thenReturn(Optional.empty());
  }

  @Test
  public void itSendsANoopWhenTheIdleEventIsHandled() {
    handler.triggerIdle();

    verify(channel).writeAndFlush(new DefaultSmtpRequest(SmtpCommand.NOOP));
  }

  @Test
  public void itDoesNotSendAnotherNoopIfOneIsPending() {
    handler.triggerIdle();
    handler.triggerIdle();

    verify(channel, times(1)).writeAndFlush(new DefaultSmtpRequest(SmtpCommand.NOOP));
  }

  @Test
  public void itDoesNotSendANoopIfACommandResponseIsPending() {
    when(responseHandler.getPendingResponseDebugString()).thenReturn(Optional.of("test"));

    handler.triggerIdle();

    verifyZeroInteractions(channel);
  }

  @Test
  public void itDoesNotPropagateReadEventsIfExpectingANoopResponse() throws Exception {
    handler.triggerIdle();
    handler.channelRead(context, OK_RESPONSE);

    verify(context, never()).fireChannelRead(any());
  }

  @Test
  public void itThrowsAnExceptionIfTheNoopResponseIsAnError() throws Exception {
    handler.triggerIdle();
    assertThatThrownBy(() -> handler.channelRead(context, ERROR_RESPONSE))
      .isInstanceOf(NoopErrorResponseException.class)
      .hasMessageEndingWith("Received error in response to NOOP (400 that didn't work)");

    verify(context, never()).fireChannelRead(any());
  }

  @Test
  public void itPropagatesReadEventsIfNotExpectingANoopResponse() throws Exception {
    handler.channelRead(context, OK_RESPONSE);

    verify(context).fireChannelRead(OK_RESPONSE);
  }

  @Test
  public void itPropagatesReadEventsOnceANoopResponseHasBeenProcessed() throws Exception {
    handler.triggerIdle();

    // response suppressed
    handler.channelRead(context, OK_RESPONSE);
    verify(context, never()).fireChannelRead(any());

    // response propagated
    handler.channelRead(context, OK_RESPONSE);
    verify(context).fireChannelRead(OK_RESPONSE);
  }

  @Test
  public void itPropagatesReadEventsIfTheyAreNotSmtpResponse() throws Exception {
    Object obj = new Object();
    handler.triggerIdle();

    handler.channelRead(context, obj);

    verify(context).fireChannelRead(obj);
  }

  @Test
  public void itQueuesWritesWhileANoopResponseIsPending() throws Exception {
    Object message1 = new Object();
    Object message2 = new Object();
    ChannelPromise promise1 = mockPromiseWithUnvoid();
    ChannelPromise promise2 = mockPromiseWithUnvoid();

    handler.triggerIdle();

    // waiting for noop response
    handler.write(context, message1, promise1);
    handler.write(context, message2, promise2);

    // not sent yet
    verify(context, never()).write(any(), any());

    // noop response received
    handler.channelRead(context, OK_RESPONSE);

    // sent now
    verify(context).write(message1, promise1);
    verify(context).write(message2, promise2);
  }

  private ChannelPromise mockPromiseWithUnvoid() {
    // IdleStateHandler calls unvoid on the promise to ensure it can attach a listener
    ChannelPromise p = mock(ChannelPromise.class);
    when(p.unvoid()).thenReturn(p);
    return p;
  }

  private class TestHandler extends KeepAliveHandler {
    TestHandler(ResponseHandler responseHandler, String connectionId, Duration idleTimeout) {
      super(responseHandler, connectionId, idleTimeout);
    }

    void triggerIdle() {
      try {
        channelIdle(context, IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
