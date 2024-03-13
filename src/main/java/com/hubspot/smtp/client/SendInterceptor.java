package com.hubspot.smtp.client;

import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * An extension point that supports intercepting commands and data before they are sent.
 *
 * <p>There are three methods to implement, each of which executes "around" the sending of
 * a request, data or pipelined sequence of requests. The @{code aroundRequest} and
 * {@code aroundPipelinedSequence} methods each receive an argument describing what is being
 * sent. All methods receive a {@code Supplier<CompletableFuture<List<SmtpResponse>>>} called
 * {@code next} that returns a future that will complete when the send has finished.
 *
 * <p>As an example, consider an interceptor used for timing commands. It can record the time
 * before the command/data is sent, then log the difference when the {@code next} future completes.
 *
 * <pre>{@code
 *  class TimingInterceptor implements SendInterceptor {
 *    private static final Logger LOG = LoggerFactory.getLogger(TimingInterceptor.class);
 *
 *    public CompletableFuture<List<SmtpResponse>> aroundRequest(SmtpRequest request, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
 *      long startedAt = System.currentTimeMillis();
 *      return next.get().whenComplete((response, throwable) -> LOG.debug("{}: {}ms", request, System.currentTimeMillis() - startedAt);
 *    }
 *
 *    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
 *      long startedAt = System.currentTimeMillis();
 *      return next.get().whenComplete((response, throwable) -> LOG.debug("data: {}ms", System.currentTimeMillis() - startedAt);
 *    }
 *
 *    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(List<SmtpRequest> requests, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
 *      long startedAt = System.currentTimeMillis();
 *      return next.get().whenComplete((response, throwable) -> LOG.debug("{}: {}ms", requests, System.currentTimeMillis() - startedAt);
 *    }
 *  }}</pre>
 *
 *  @see CompositeSendInterceptor
 */
public interface SendInterceptor {
  /**
   * Called before a request is sent.
   *
   * @param  request the request that will be sent
   * @param  next supplies a future that will complete when the request has been sent and a response received
   * @return {@code next.get()}, a {@code CompletableFuture} derived from it, or an exceptional future if the send should
   *         be aborted
   */
  CompletableFuture<List<SmtpResponse>> aroundRequest(
    SmtpRequest request,
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  );

  /**
   * Called before data is sent.
   *
   * @param  next supplies a future that will complete when the data has been sent and a response received
   * @return {@code next.get()}, a {@code CompletableFuture} derived from it, or an exceptional future if the send should
   *         be aborted
   */
  CompletableFuture<List<SmtpResponse>> aroundData(
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  );

  /**
   * Called before a pipelined series of requests is sent.
   *
   * @param  requests the requests that will be sent
   * @param  next supplies a future that will complete when the requests have been sent and responses received
   * @return {@code next.get()}, a {@code CompletableFuture} derived from it, or an exceptional future if the send should
   *         be aborted
   */
  CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(
    List<SmtpRequest> requests,
    Supplier<CompletableFuture<List<SmtpResponse>>> next
  );
}
