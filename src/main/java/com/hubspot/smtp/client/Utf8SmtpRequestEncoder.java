package com.hubspot.smtp.client;

import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.smtp.SmtpRequest;

// Based very closely on SmtpRequestEncoder, but supports utf8 parameters
// and doesn't require the LastSmtpContent because we always send a single content object
public final class Utf8SmtpRequestEncoder extends MessageToMessageEncoder<Object> {
  private static final byte[] CRLF = {'\r', '\n'};
  private static final byte SP = ' ';

  @Override
  public boolean acceptOutboundMessage(Object msg) throws Exception {
    return msg instanceof SmtpRequest;
  }

  @Override
  protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
    if (!(msg instanceof SmtpRequest)) {
      return;
    }

    boolean release = true;
    final ByteBuf buffer = ctx.alloc().buffer();

    try {
      final SmtpRequest req = (SmtpRequest) msg;

      ByteBufUtil.writeAscii(buffer, req.command().name());
      writeParameters(req.parameters(), buffer);
      buffer.writeBytes(CRLF);

      out.add(buffer);
      release = false;
    } finally {
      if (release) {
        buffer.release();
      }
    }
  }

  private static void writeParameters(List<CharSequence> parameters, ByteBuf out) {
    if (parameters.isEmpty()) {
      return;
    }

    out.writeByte(SP);

    if (parameters instanceof RandomAccess) {
      int sizeMinusOne = parameters.size() - 1;
      for (int i = 0; i < sizeMinusOne; i++) {
        ByteBufUtil.writeUtf8(out, parameters.get(i));
        out.writeByte(SP);
      }

      ByteBufUtil.writeUtf8(out, parameters.get(sizeMinusOne));

    } else {
      Iterator<CharSequence> params = parameters.iterator();
      while (true) {
        ByteBufUtil.writeUtf8(out, params.next());
        if (params.hasNext()) {
          out.writeByte(SP);
        } else {
          break;
        }
      }
    }
  }
}
