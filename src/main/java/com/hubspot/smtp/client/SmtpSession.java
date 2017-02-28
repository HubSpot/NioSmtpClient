package com.hubspot.smtp.client;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

import io.netty.channel.Channel;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;

public class SmtpSession {
  private final Channel channel;
  private final ResponseHandler responseHandler;

  private volatile EnumSet<SupportedExtensions> supportedExtensions = EnumSet.noneOf(SupportedExtensions.class);

  SmtpSession(Channel channel, ResponseHandler responseHandler) {
    this.channel = channel;
    this.responseHandler = responseHandler;
  }

  public CompletableFuture<Void> close() {
    CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    this.channel.close().addListener(f -> closeFuture.complete(null));
    return closeFuture;
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpRequest request) {
    CompletableFuture<SmtpResponse> responseFuture = responseHandler.createResponseFuture();
    channel.writeAndFlush(request);

    return responseFuture.thenApply(r -> new SmtpClientResponse(r, this));
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpContent... contents) {
    CompletableFuture<SmtpResponse> responseFuture = responseHandler.createResponseFuture();

    for (SmtpContent c : contents) {
      channel.write(c);
    }

    channel.flush();

    return responseFuture.thenApply(r -> new SmtpClientResponse(r, this));
  }

  public void setSupportedExtensions(EnumSet<SupportedExtensions> supportedExtensions) {
    this.supportedExtensions = supportedExtensions;
  }

  public boolean isSupported(SupportedExtensions ext) {
    return supportedExtensions.contains(ext);
  }
}
