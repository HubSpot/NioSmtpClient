package com.hubspot.smtp.client;

import com.google.common.collect.Lists;
import com.hubspot.smtp.utils.SmtpResponses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class KeepAliveHandler extends IdleStateHandler {

  private static final Logger LOG = LoggerFactory.getLogger(KeepAliveHandler.class);

  private final ResponseHandler responseHandler;
  private final String connectionId;
  private final List<PendingWrite> pendingWrites = Lists.newArrayList();

  private boolean expectingNoopResponse;

  KeepAliveHandler(
    ResponseHandler responseHandler,
    String connectionId,
    Duration idleTimeout
  ) {
    // just track overall idle time; disable individual reader & writer timers by passing zero
    super(0, 0, Math.toIntExact(idleTimeout.getSeconds()));
    this.responseHandler = responseHandler;
    this.connectionId = connectionId;
  }

  @Override
  protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt)
    throws Exception {
    LOG.debug("[{}] Sending NOOP to keep the connection alive", connectionId);

    if (expectingNoopResponse) {
      LOG.warn(
        "[{}] Did not receive a response to our last NOOP, will not send another",
        connectionId
      );
      return;
    }

    Optional<String> debugString = responseHandler.getPendingResponseDebugString();
    if (debugString.isPresent()) {
      LOG.warn(
        "[{}] Waiting for a response to [{}], will not send a NOOP to keep the connection alive",
        connectionId,
        debugString.get()
      );
    } else {
      LOG.debug("[{}] Sending NOOP", connectionId);
      ctx.channel().writeAndFlush(new DefaultSmtpRequest(SmtpCommand.NOOP));
      expectingNoopResponse = true;
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (expectingNoopResponse && msg instanceof SmtpResponse) {
      LOG.debug(
        "[{}] NOOP response received {}",
        connectionId,
        SmtpResponses.toString((SmtpResponse) msg)
      );
      swallowNoopResponse((SmtpResponse) msg);
      sendPendingWrites(ctx);
      return;
    }

    super.channelRead(ctx, msg);
  }

  private void swallowNoopResponse(SmtpResponse response)
    throws NoopErrorResponseException {
    expectingNoopResponse = false;

    if (SmtpResponses.isError(response)) {
      LOG.warn(
        "[{}] Received error {} in response to NOOP",
        connectionId,
        SmtpResponses.toString(response)
      );
      throw new NoopErrorResponseException(
        connectionId,
        response,
        "Received error in response to NOOP"
      );
    }
  }

  private void sendPendingWrites(ChannelHandlerContext ctx) throws Exception {
    for (PendingWrite w : pendingWrites) {
      write(ctx, w.msg, w.promise);
    }
    pendingWrites.clear();
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
    throws Exception {
    // queue any pending writes until the response is received
    if (expectingNoopResponse) {
      pendingWrites.add(new PendingWrite(msg, promise));
      return;
    }

    super.write(ctx, msg, promise);
  }

  private static class PendingWrite {

    private Object msg;
    private ChannelPromise promise;

    PendingWrite(Object msg, ChannelPromise promise) {
      this.msg = msg;
      this.promise = promise;
    }
  }
}
