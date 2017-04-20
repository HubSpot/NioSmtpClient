package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;

public interface Hook {
  CompletableFuture<List<SmtpResponse>> aroundCommand(SmtpCommand command, Supplier<CompletableFuture<List<SmtpResponse>>> next);
  CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next);
}
