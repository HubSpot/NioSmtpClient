package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;

public class CompositeSendInterceptor implements SendInterceptor {
  private final SendInterceptor rootSendInterceptor;

  public static CompositeSendInterceptor of(SendInterceptor... sendInterceptors) {
    return new CompositeSendInterceptor(Lists.newArrayList(sendInterceptors));
  }

  public static CompositeSendInterceptor of(List<SendInterceptor> interceptors) {
    return new CompositeSendInterceptor(interceptors);
  }

  private CompositeSendInterceptor(List<SendInterceptor> sendInterceptors) {
    Preconditions.checkNotNull(sendInterceptors);
    Preconditions.checkArgument(!sendInterceptors.isEmpty(), "sendInterceptors must not be empty");

    SendInterceptor root = sendInterceptors.get(0);

    for (int i = 1; i < sendInterceptors.size(); i++) {
      root = new InterceptorWrapper(root, sendInterceptors.get(i));
    }

    rootSendInterceptor = root;
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootSendInterceptor.aroundCommand(command, next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootSendInterceptor.aroundData(next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootSendInterceptor.aroundPipelinedSequence(next);
  }

  private static class InterceptorWrapper implements SendInterceptor {
    private final SendInterceptor thisSendInterceptor;
    private final SendInterceptor nextSendInterceptor;

    public InterceptorWrapper(SendInterceptor thisSendInterceptor, SendInterceptor nextSendInterceptor) {
      this.thisSendInterceptor = thisSendInterceptor;
      this.nextSendInterceptor = nextSendInterceptor;
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisSendInterceptor.aroundCommand(command, () -> nextSendInterceptor.aroundCommand(command, next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisSendInterceptor.aroundData(() -> nextSendInterceptor.aroundData(next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisSendInterceptor.aroundData(() -> nextSendInterceptor.aroundPipelinedSequence(next));
    }
  }
}
