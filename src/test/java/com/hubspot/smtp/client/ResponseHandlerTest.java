package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpResponse;

public class ResponseHandlerTest {
  private ResponseHandler responseHandler;
  private ChannelHandlerContext context;
  private static final DefaultSmtpResponse SMTP_RESPONSE = new DefaultSmtpResponse(250);

  @Before
  public void setup() {
    responseHandler = new ResponseHandler();
    context = mock(ChannelHandlerContext.class);
  }

  @Test
  public void itCompletesExceptionallyIfAnExceptionIsCaught() throws Exception {
    CompletableFuture<SmtpResponse> f = responseHandler.createResponseFuture();
    Exception testException = new Exception("test");

    responseHandler.exceptionCaught(context, testException);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class).hasCause(testException);
  }

  @Test
  public void itCompletesWithAResponseWhenHandled() throws Exception {
    CompletableFuture<SmtpResponse> f = responseHandler.createResponseFuture();

    responseHandler.channelRead(context, SMTP_RESPONSE);

    assertThat(f.isCompletedExceptionally()).isFalse();
    assertThat(f.get()).isEqualTo(SMTP_RESPONSE);
  }

  @Test
  public void itDoesNotCompleteWhenSomeOtherObjectIsRead() throws Exception {
    CompletableFuture<SmtpResponse> f = responseHandler.createResponseFuture();

    responseHandler.channelRead(context, "unexpected");

    assertThat(f.isDone()).isFalse();
  }

  @Test
  public void itOnlyCreatesOneResponseFutureAtATime() {
    assertThat(responseHandler.createResponseFuture()).isNotNull();

    assertThatThrownBy(() -> responseHandler.createResponseFuture())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Cannot wait for a response while one is already pending");
  }

  @Test
  public void itCanCreateNewFuturesOnceAResponseHasArrived() throws Exception {
    responseHandler.createResponseFuture();
    responseHandler.channelRead(context, SMTP_RESPONSE);

    responseHandler.createResponseFuture();
  }

  @Test
  public void itCanCreateNewFuturesOnceAnExceptionIsHandled() throws Exception {
    responseHandler.createResponseFuture();
    responseHandler.exceptionCaught(context, new Exception("test"));

    responseHandler.createResponseFuture();
  }
}
