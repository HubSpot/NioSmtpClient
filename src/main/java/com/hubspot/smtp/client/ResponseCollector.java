package com.hubspot.smtp.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import io.netty.handler.codec.smtp.SmtpResponse;

/**
 * Counts SMTP responses and wraps a future that will complete when all responses have been received.
 *
 */
class ResponseCollector {
  private final CompletableFuture<List<SmtpResponse>> future;
  private final List<SmtpResponse> responses;
  private final Supplier<String> debugString;
  private int remainingResponses;

  ResponseCollector(int expectedResponses, Supplier<String> debugString) {
    this.remainingResponses = expectedResponses;
    this.debugString = debugString;

    responses = Lists.newArrayListWithExpectedSize(expectedResponses);
    future = new CompletableFuture<>();
  }

  CompletableFuture<List<SmtpResponse>> getFuture() {
    return future;
  }

  public String getDebugString() {
    return debugString.get();
  }

  boolean addResponse(SmtpResponse response) {
    Preconditions.checkState(remainingResponses > 0, "All the responses have already been collected");
    remainingResponses--;

    responses.add(response);

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
