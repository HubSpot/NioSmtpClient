package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;

public class CompositeHook implements Hook {
  private final Hook rootHook;

  public static CompositeHook of(Hook... hooks) {
    return new CompositeHook(Lists.newArrayList(hooks));
  }

  public static CompositeHook of(List<Hook> hooks) {
    return new CompositeHook(hooks);
  }

  private CompositeHook(List<Hook> hooks) {
    Preconditions.checkNotNull(hooks);
    Preconditions.checkArgument(!hooks.isEmpty(), "hooks must not be empty");

    Hook root = hooks.get(0);

    for (int i = 1; i < hooks.size(); i++) {
      root = new HookWrapper(root, hooks.get(i));
    }

    rootHook = root;
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootHook.aroundCommand(command, next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootHook.aroundData(next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
    return rootHook.aroundPipelinedSequence(next);
  }

  private static class HookWrapper implements Hook {
    private final Hook thisHook;
    private final Hook nextHook;

    public HookWrapper(Hook thisHook, Hook nextHook) {
      this.thisHook = thisHook;
      this.nextHook = nextHook;
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisHook.aroundCommand(command, () -> nextHook.aroundCommand(command, next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisHook.aroundData(() -> nextHook.aroundData(next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return thisHook.aroundData(() -> nextHook.aroundPipelinedSequence(next));
    }
  }
}
