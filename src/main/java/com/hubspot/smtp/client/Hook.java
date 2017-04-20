package com.hubspot.smtp.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.netty.handler.codec.smtp.SmtpCommand;

public interface Hook {
  CompletableFuture<SmtpClientResponse> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<SmtpClientResponse>> next);
  CompletableFuture<SmtpClientResponse> aroundData(Supplier<CompletableFuture<SmtpClientResponse>> next);
}
