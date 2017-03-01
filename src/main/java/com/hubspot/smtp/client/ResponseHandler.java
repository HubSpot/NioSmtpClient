package com.hubspot.smtp.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.SmtpResponse;

class ResponseHandler extends ChannelInboundHandlerAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(ResponseHandler.class);

  private final AtomicReference<ResponseCollector> responseCollector = new AtomicReference<>();

  CompletableFuture<SmtpResponse[]> createResponseFuture(int expectedResponses, Supplier<String> debugStringSupplier) {
    ResponseCollector collector = new ResponseCollector(expectedResponses, debugStringSupplier);

    boolean success = responseCollector.compareAndSet(null, collector);
    if (!success) {
      ResponseCollector previousCollector = this.responseCollector.get();
      if (previousCollector == null) {
        return createResponseFuture(expectedResponses, debugStringSupplier);
      }

      throw new IllegalStateException(String.format("Cannot wait for a response to [%s] because we're still waiting for a response to [%s]",
          collector.getDebugString(), previousCollector.getDebugString()));
    }

    return collector.getFuture();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof SmtpResponse) {
      ResponseCollector collector = responseCollector.get();

      if (collector == null) {
        LOG.warn("Unexpected response received: {}", msg);
      } else {
        boolean complete = collector.addResponse((SmtpResponse) msg);
        if (complete) {
          // because only the event loop code sets this field when it is non-null,
          // and because channelRead is always run in the same thread, we can
          // be sure this value hasn't changed since we read it earlier in this method
          responseCollector.set(null);
          collector.complete();
        }
      }
    }

    ctx.fireChannelRead(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    ResponseCollector collector = responseCollector.getAndSet(null);
    if (collector != null) {
      collector.completeExceptionally(cause);
    }
  }
}
