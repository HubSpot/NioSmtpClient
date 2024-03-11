package com.hubspot.smtp.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A chain of {@link SendInterceptor} instances that will call each other in turn.
 *
 * <p>Construct a chain by passing a list of {@code SendInterceptor}s to the
 * {@code of} method.
 *
 * <pre>{@code
 *  CompositeSendInterceptor composite = CompositeSendInterceptor.of(
 *    new SendInterceptorA(),
 *    new SendInterceptorB(),
 *    new SendInterceptorC());}</pre>
 *
 * <p>With this chain, {@code SendInterceptorA} will be called first, and
 * the {@code next} parameter of each of its {@code around} methods
 * will refer to the future returned by {@code SendInterceptorB}.
 *
 * <p>This class is thread-safe.
 */
public class CompositeSendInterceptor implements SendInterceptor {

  private final SendInterceptor rootInterceptor;
  private final List<SendInterceptor> sendInterceptors;

  /**
   * Creates a chain of {@link SendInterceptor} instances that will call each other in turn.
   */
  public static CompositeSendInterceptor of(SendInterceptor... sendInterceptors) {
    return new CompositeSendInterceptor(Lists.newArrayList(sendInterceptors));
  }

  /**
   * Creates a chain of {@link SendInterceptor} instances that will call each other in turn.
   */
  public static CompositeSendInterceptor of(List<SendInterceptor> interceptors) {
    return new CompositeSendInterceptor(interceptors);
  }

  private CompositeSendInterceptor(List<SendInterceptor> sendInterceptors) {
    Preconditions.checkNotNull(sendInterceptors);
    Preconditions.checkArgument(
      !sendInterceptors.isEmpty(),
      "sendInterceptors must not be empty"
    );

    SendInterceptor rootInterceptor = sendInterceptors.get(0);

    for (int i = 1; i < sendInterceptors.size(); i++) {
      rootInterceptor = new InterceptorWrapper(rootInterceptor, sendInterceptors.get(i));
    }

    this.rootInterceptor = rootInterceptor;
    this.sendInterceptors = sendInterceptors;
  }

  @VisibleForTesting
  public List<SendInterceptor> getSendInterceptors() {
    return sendInterceptors;
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundRequest(
    SmtpRequest request,
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  ) {
    return rootInterceptor.aroundRequest(request, next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundData(
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  ) {
    return rootInterceptor.aroundData(next);
  }

  @Override
  public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(
    List<SmtpRequest> requests,
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  ) {
    return rootInterceptor.aroundPipelinedSequence(requests, next);
  }

  private static class InterceptorWrapper implements SendInterceptor {

    private final SendInterceptor thisInterceptor;
    private final SendInterceptor nextInterceptor;

    InterceptorWrapper(SendInterceptor thisInterceptor, SendInterceptor nextInterceptor) {
      this.thisInterceptor = thisInterceptor;
      this.nextInterceptor = nextInterceptor;
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundRequest(
      SmtpRequest request,
      Supplier<CompletableFuture<List<SmtpResponse>>> next
    ) {
      return thisInterceptor.aroundRequest(
        request,
        () -> nextInterceptor.aroundRequest(request, next)
      );
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(
      Supplier<CompletableFuture<List<SmtpResponse>>> next
    ) {
      return thisInterceptor.aroundData(() -> nextInterceptor.aroundData(next));
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(
      List<SmtpRequest> requests,
      Supplier<CompletableFuture<List<SmtpResponse>>> next
    ) {
      return thisInterceptor.aroundPipelinedSequence(
        requests,
        () -> nextInterceptor.aroundPipelinedSequence(requests, next)
      );
    }
  }
}
