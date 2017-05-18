package com.hubspot.smtp.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;

/**
 * A Netty handler that collects responses to SMTP commands and makes them available.
 *
 */
class ResponseHandler extends SimpleChannelInboundHandler<SmtpResponse> {
  private static final Logger LOG = LoggerFactory.getLogger(ResponseHandler.class);
  private static final HashedWheelTimer TIMER = new HashedWheelTimer(new ThreadFactoryBuilder().setDaemon(true).setNameFormat("response-timer-%d").build());

  private final AtomicReference<ResponseCollector> responseCollector = new AtomicReference<>();
  private final String connectionId;
  private final Optional<Duration> responseTimeout;
  private final Optional<Consumer<Throwable>> exceptionHandler;

  ResponseHandler(String connectionId, Optional<Duration> responseTimeout, Optional<Consumer<Throwable>> exceptionHandler) {
    this.connectionId = connectionId;
    this.responseTimeout = responseTimeout;
    this.exceptionHandler = exceptionHandler;
  }

  CompletableFuture<List<SmtpResponse>> createResponseFuture(int expectedResponses, Supplier<String> debugStringSupplier) {
    ResponseCollector collector = new ResponseCollector(expectedResponses, debugStringSupplier);

    boolean success = responseCollector.compareAndSet(null, collector);
    if (!success) {
      ResponseCollector previousCollector = this.responseCollector.get();
      if (previousCollector == null) {
        return createResponseFuture(expectedResponses, debugStringSupplier);
      }

      throw new IllegalStateException(String.format("[%s] Cannot wait for a response to [%s] because we're still waiting for a response to [%s]",
          connectionId, collector.getDebugString(), previousCollector.getDebugString()));
    }

    // although the future field may have been written in another thread,
    // the compareAndSet call above has volatile semantics and
    // ensures the write will be visible
    CompletableFuture<List<SmtpResponse>> responseFuture = collector.getFuture();

    applyResponseTimeout(responseFuture);

    return responseFuture;
  }

  private void applyResponseTimeout(CompletableFuture<List<SmtpResponse>> responseFuture) {
    responseTimeout.ifPresent(timeout -> {
      Timeout hwtTimeout = TIMER.newTimeout(ignored -> responseFuture.completeExceptionally(new TimeoutException()), timeout.toMillis(), TimeUnit.MILLISECONDS);
      responseFuture.whenComplete((ignored1, ignored2) -> hwtTimeout.cancel());
    });
  }

  boolean isResponsePending() {
    return responseCollector.get() != null;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, SmtpResponse msg) throws Exception {
    ResponseCollector collector = responseCollector.get();

    if (collector == null) {
      LOG.warn("[{}] Unexpected response received: {}", connectionId, msg);
    } else {
      boolean complete = collector.addResponse(msg);
      if (complete) {
        // because only the event loop code sets this field when it is non-null,
        // and because channelRead is always run in the same thread, we can
        // be sure this value hasn't changed since we read it earlier in this method
        responseCollector.set(null);
        collector.complete();
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cause instanceof ReadTimeoutException) {
      LOG.warn("[{}] The channel was closed because a read timed out", connectionId);
    }

    ResponseCollector collector = responseCollector.getAndSet(null);
    if (collector != null) {
      collector.completeExceptionally(cause);
    } else {
      // this exception can't get back to the client via a future,
      // use the connection exception handler if possible
      if (exceptionHandler.isPresent()) {
        exceptionHandler.get().accept(cause);
      } else {
        super.exceptionCaught(ctx, cause);
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ResponseCollector collector = responseCollector.get();

    if (collector != null) {
      collector.completeExceptionally(new ChannelClosedException(connectionId, "Handled channelInactive while waiting for a response to [" + collector.getDebugString() + "]"));
    }

    super.channelInactive(ctx);
  }
}
