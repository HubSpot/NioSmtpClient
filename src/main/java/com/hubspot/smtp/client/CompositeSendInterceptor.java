package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;

public class CompositeSendInterceptor implements SendInterceptor {
  private final SendInterceptor rootInterceptor;

  public static CompositeSendInterceptor of(SendInterceptor... sendInterceptors) {
    return new CompositeSendInterceptor(Lists.newArrayList(sendInterceptors));
  }

  public static CompositeSendInterceptor of(List<SendInterceptor> interceptors) {
    return new CompositeSendInterceptor(interceptors);
  }

  private CompositeSendInterceptor(List<SendInterceptor> sendInterceptors) {
    Preconditions.checkNotNull(sendInterceptors);
    Preconditions.checkArgument(!sendInterceptors.isEmpty(), "sendInterceptors must not be empty");

    SendInterceptor rootInterceptor = sendInterceptors.get(0);

    for (int i = 1; i < sendInterceptors.size(); i++) {
      rootInterceptor = new InterceptorWrapper(rootInterceptor, sendInterceptors.get(i));
    }

    this.rootInterceptor = rootInterceptor;
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootInterceptor.aroundCommand(command, next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootInterceptor.aroundData(next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootInterceptor.aroundPipelinedSequence(next);
  }

  private static class InterceptorWrapper implements SendInterceptor {
    private final SendInterceptor thisInterceptor;
    private final SendInterceptor nextInterceptor;

    public InterceptorWrapper(SendInterceptor thisInterceptor, SendInterceptor nextInterceptor) {
      this.thisInterceptor = thisInterceptor;
      this.nextInterceptor = nextInterceptor;
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisInterceptor.aroundCommand(command, () -> nextInterceptor.aroundCommand(command, next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisInterceptor.aroundData(() -> nextInterceptor.aroundData(next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisInterceptor.aroundData(() -> nextInterceptor.aroundPipelinedSequence(next));
    }
  }
}
