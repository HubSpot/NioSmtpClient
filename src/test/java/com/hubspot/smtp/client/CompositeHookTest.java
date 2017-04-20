package com.hubspot.smtp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.Test;

import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;

public class CompositeHookTest {
  private static final SmtpClientResponse DEFAULT_RESPONSE = new SmtpClientResponse(null, new DefaultSmtpResponse(250, "OK"));

  private static final Hook RESPONSE_HOOK = new Hook() {
    @Override
    public CompletableFuture<SmtpClientResponse> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<SmtpClientResponse>> next) {
      return CompletableFuture.completedFuture(DEFAULT_RESPONSE);
    }

    @Override
    public CompletableFuture<SmtpClientResponse> aroundData(Supplier<CompletableFuture<SmtpClientResponse>> next) {
      return CompletableFuture.completedFuture(DEFAULT_RESPONSE);
    }
  };

  private static final Hook ALWAYS_FAILS_HOOK = new Hook() {
    @Override
    public CompletableFuture<SmtpClientResponse> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<SmtpClientResponse>> next) {
      CompletableFuture<SmtpClientResponse> future = new CompletableFuture<>();
      future.completeExceptionally(new Exception("fail"));
      return future;
    }

    @Override
    public CompletableFuture<SmtpClientResponse> aroundData(Supplier<CompletableFuture<SmtpClientResponse>> next) {
      CompletableFuture<SmtpClientResponse> future = new CompletableFuture<>();
      future.completeExceptionally(new Exception("fail"));
      return future;
    }
  };

  private static final Hook PASS_THROUGH_HOOK = new Hook() {
    @Override
    public CompletableFuture<SmtpClientResponse> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<SmtpClientResponse>> next) {
      return next.get();
    }

    @Override
    public CompletableFuture<SmtpClientResponse> aroundData(Supplier<CompletableFuture<SmtpClientResponse>> next) {
      return next.get();
    }
  };

  @Test
  public void itWrapsHooks() throws Exception {
    SmtpClientResponse responses = CompositeHook.of(RESPONSE_HOOK).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itOrdersHooks() throws Exception {
    SmtpClientResponse responses = CompositeHook.of(PASS_THROUGH_HOOK, RESPONSE_HOOK).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itExecutesHooksLazily() throws Exception {
    Hook mockHook = mock(Hook.class);
    CompletableFuture<SmtpClientResponse> future = CompositeHook.of(ALWAYS_FAILS_HOOK, mockHook).aroundCommand(SmtpCommand.MAIL, null);

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(mockHook, never()).aroundCommand(any(), any());
  }
}
