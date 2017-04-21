package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;

public interface SendInterceptor {
  CompletableFuture<List<SmtpResponse>> aroundRequest(SmtpRequest request, Supplier<CompletableFuture<List<SmtpResponse>>> next);
  CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next);
  CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(List<SmtpRequest> requests, Supplier<CompletableFuture<List<SmtpResponse>>> next);
}
