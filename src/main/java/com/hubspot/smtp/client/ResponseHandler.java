package com.hubspot.smtp.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.SmtpResponse;

class ResponseHandler extends ChannelInboundHandlerAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(ResponseHandler.class);

  private final AtomicReference<CompletableFuture<SmtpResponse>> responseFuture = new AtomicReference<>();

  CompletableFuture<SmtpResponse> createResponseFuture() {
    CompletableFuture<SmtpResponse> f = new CompletableFuture<>();

    boolean success = responseFuture.compareAndSet(null, f);
    Preconditions.checkState(success, "Cannot wait for a response while one is already pending");

    return f;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof SmtpResponse) {
      CompletableFuture<SmtpResponse> f = responseFuture.getAndSet(null);
      if (f == null) {
        LOG.warn("Unexpected response received: " + msg);
      } else {
        f.complete((SmtpResponse) msg);
      }
    }

    ctx.fireChannelRead(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    CompletableFuture<SmtpResponse> f = responseFuture.getAndSet(null);
    if (f != null) {
      f.completeExceptionally(cause);
    }
  }
}
