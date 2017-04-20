package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.assertj.core.util.Lists;
import org.junit.Test;

import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;

public class CompositeSendInterceptorTest {
  private static final List<SmtpResponse> DEFAULT_RESPONSE = Lists.newArrayList(new DefaultSmtpResponse(250, "OK"));

  private static final SendInterceptor RESPONSE_SEND_INTERCEPTOR = new SendInterceptor() {
    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return CompletableFuture.completedFuture(DEFAULT_RESPONSE);
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return CompletableFuture.completedFuture(DEFAULT_RESPONSE);
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return CompletableFuture.completedFuture(DEFAULT_RESPONSE);
    }
  };

  private static final SendInterceptor ALWAYS_FAILS_SEND_INTERCEPTOR = new SendInterceptor() {
    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return getFailedFuture();
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return getFailedFuture();
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return getFailedFuture();
    }

    private CompletableFuture<List<SmtpResponse>> getFailedFuture() {
      CompletableFuture<List<SmtpResponse>> future = new CompletableFuture<>();
      future.completeExceptionally(new Exception("fail"));
      return future;
    }
  };

  private static final SendInterceptor PASS_THROUGH_SEND_INTERCEPTOR = new SendInterceptor() {
    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return next.get();
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return next.get();
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return next.get();
    }
  };

  @Test
  public void itWrapsInterceptors() throws Exception {
    List<SmtpResponse> responses = CompositeSendInterceptor.of(RESPONSE_SEND_INTERCEPTOR).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itOrdersInterceptors() throws Exception {
    List<SmtpResponse> responses = CompositeSendInterceptor.of(PASS_THROUGH_SEND_INTERCEPTOR, RESPONSE_SEND_INTERCEPTOR).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itExecutesInterceptorsLazily() throws Exception {
    SendInterceptor mockSendInterceptor = mock(SendInterceptor.class);
    CompletableFuture<List<SmtpResponse>> future = CompositeSendInterceptor.of(ALWAYS_FAILS_SEND_INTERCEPTOR, mockSendInterceptor).aroundCommand(SmtpCommand.MAIL, null);

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(mockSendInterceptor, never()).aroundCommand(any(), any());
  }
}
