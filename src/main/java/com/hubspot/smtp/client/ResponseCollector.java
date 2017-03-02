package com.hubspot.smtp.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;

import io.netty.handler.codec.smtp.SmtpResponse;

class ResponseCollector {
  private final CompletableFuture<SmtpResponse[]> future;
  private final SmtpResponse[] responses;
  private final Supplier<String> debugString;
  private int remainingResponses;

  ResponseCollector(int expectedResponses, Supplier<String> debugString) {
    this.remainingResponses = expectedResponses;
    this.debugString = debugString;

    responses = new SmtpResponse[expectedResponses];
    future = new CompletableFuture<>();
  }

  CompletableFuture<SmtpResponse[]> getFuture() {
    return future;
  }

  public String getDebugString() {
    return debugString.get();
  }

  boolean addResponse(SmtpResponse response) {
    Preconditions.checkState(remainingResponses > 0, "All the responses have already been collected");
    remainingResponses--;

    responses[responses.length - remainingResponses - 1] = response;

    return remainingResponses == 0;
  }

  void complete() {
    Preconditions.checkState(remainingResponses == 0, "Still waiting for " + remainingResponses + " responses");

    future.complete(responses);
  }

  void completeExceptionally(Throwable cause) {
    future.completeExceptionally(cause);
  }
}
