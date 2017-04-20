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

public class CompositeHookTest {
  private static final List<SmtpResponse> DEFAULT_RESPONSE = Lists.newArrayList(new DefaultSmtpResponse(250, "OK"));

  private static final Hook RESPONSE_HOOK = new Hook() {
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

  private static final Hook ALWAYS_FAILS_HOOK = new Hook() {
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

  private static final Hook PASS_THROUGH_HOOK = new Hook() {
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
  public void itWrapsHooks() throws Exception {
    List<SmtpResponse> responses = CompositeHook.of(RESPONSE_HOOK).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itOrdersHooks() throws Exception {
    List<SmtpResponse> responses = CompositeHook.of(PASS_THROUGH_HOOK, RESPONSE_HOOK).aroundCommand(SmtpCommand.MAIL, null).get();

    assertThat(responses).isEqualTo(DEFAULT_RESPONSE);
  }

  @Test
  public void itExecutesHooksLazily() throws Exception {
    Hook mockHook = mock(Hook.class);
    CompletableFuture<List<SmtpResponse>> future = CompositeHook.of(ALWAYS_FAILS_HOOK, mockHook).aroundCommand(SmtpCommand.MAIL, null);

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(mockHook, never()).aroundCommand(any(), any());
  }
}
