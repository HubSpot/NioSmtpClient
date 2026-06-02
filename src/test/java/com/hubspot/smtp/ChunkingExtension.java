package com.hubspot.smtp;

import static com.hubspot.smtp.ChunkingChannelHandlerFactory.NETTY_CHANNEL;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.james.core.MaybeSender;
import org.apache.james.protocols.api.ProtocolSession.AttachmentKey;
import org.apache.james.protocols.api.ProtocolSession.State;
import org.apache.james.protocols.api.Request;
import org.apache.james.protocols.api.Response;
import org.apache.james.protocols.api.handler.CommandHandler;
import org.apache.james.protocols.api.handler.ExtensibleHandler;
import org.apache.james.protocols.api.handler.LineHandler;
import org.apache.james.protocols.api.handler.WiringException;
import org.apache.james.protocols.netty.HandlerConstants;
import org.apache.james.protocols.smtp.MailEnvelopeImpl;
import org.apache.james.protocols.smtp.SMTPResponse;
import org.apache.james.protocols.smtp.SMTPRetCode;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.core.esmtp.EhloExtension;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.Hook;
import org.apache.james.protocols.smtp.hook.MessageHook;

public class ChunkingExtension
  implements EhloExtension, CommandHandler<SMTPSession>, ExtensibleHandler, Hook {

  private static final AttachmentKey<MailEnvelopeImpl> MAIL_ENVELOPE = AttachmentKey.of(
    "mail envelope",
    MailEnvelopeImpl.class
  );
  private static final String BDAT_HANDLER_NAME = "BDAT handler";
  private static final Pattern BDAT_COMMAND_PATTERN = Pattern.compile(
    "(?<size>[0-9]+)(?<last> LAST)?"
  );

  private static final Response DELIVERY_SYNTAX = new SMTPResponse(
    SMTPRetCode.SYNTAX_ERROR_ARGUMENTS,
    DSNStatus.getStatus(DSNStatus.PERMANENT, DSNStatus.DELIVERY_SYNTAX) +
    " Invalid syntax"
  )
    .immutable();

  private List<MessageHook> messageHandlers;

  @Override
  public Response onCommand(SMTPSession session, Request request) {
    Matcher matcher = BDAT_COMMAND_PATTERN.matcher(request.getArgument());
    if (!matcher.matches()) {
      return DELIVERY_SYNTAX;
    }

    Channel channel = session
      .getAttachment(NETTY_CHANNEL, State.Connection)
      .orElseThrow(() ->
        new RuntimeException(
          "ChunkingChannelHandlerFactory must be used to support chunking"
        )
      );

    BdatHandler bdatHandler = new BdatHandler(channel, session);
    bdatHandler.startCapturingData(
      Integer.parseInt(matcher.group("size")),
      matcher.group("last") != null
    );

    // Return null: the response is written by the BdatHandler once the chunk has been
    // captured, mirroring how James' transport defers a response.
    return null;
  }

  @Override
  public Collection<String> getImplCommands() {
    return Collections.singletonList("BDAT");
  }

  @Override
  public List<String> getImplementedEsmtpFeatures(SMTPSession session) {
    return Lists.newArrayList("CHUNKING");
  }

  @Override
  public List<Class<?>> getMarkerInterfaces() {
    return Lists.newArrayList(MessageHook.class, LineHandler.class);
  }

  @Override
  public void wireExtensions(Class<?> interfaceName, List<?> extension)
    throws WiringException {
    // Save MessageHooks so we can tell them about mails we receive
    if (MessageHook.class.equals(interfaceName)) {
      messageHandlers = Lists.newArrayList();
      for (Object ext : extension) {
        if (ext instanceof MessageHook) {
          messageHandlers.add((MessageHook) ext);
        }
      }
    }
  }

  private class BdatHandler extends ChannelInboundHandlerAdapter {

    private final Channel channel;
    private final ChannelPipeline pipeline;
    private final SMTPSession session;

    private int currentChunkSize;
    private int bytesRead;
    private boolean isLastChunk;

    BdatHandler(Channel channel, SMTPSession session) {
      this.channel = channel;
      this.pipeline = channel.pipeline();
      this.session = session;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof ByteBuf) {
        ByteBuf buffer = (ByteBuf) msg;
        int bytesToRead = Math.min(currentChunkSize - bytesRead, buffer.readableBytes());
        buffer.readBytes(getMailEnvelope().getMessageOutputStream(), bytesToRead);
        bytesRead += bytesToRead;

        if (bytesRead == currentChunkSize) {
          stopCapturingData();
        }

        if (buffer.isReadable()) {
          ctx.fireChannelRead(buffer);
        } else {
          buffer.release();
        }

        return;
      }

      ctx.fireChannelRead(msg);
    }

    private void startCapturingData(int bytesToCapture, boolean last) {
      currentChunkSize = bytesToCapture;
      isLastChunk = last;

      pipeline.addBefore(HandlerConstants.FRAMER, BDAT_HANDLER_NAME, this);

      MailEnvelopeImpl env = new MailEnvelopeImpl();
      env.setRecipients(
        session
          .getAttachment(SMTPSession.RCPT_LIST, State.Transaction)
          .orElse(ImmutableList.of())
      );
      env.setSender(
        session
          .getAttachment(SMTPSession.SENDER, State.Transaction)
          .orElse(MaybeSender.nullSender())
      );

      session.setAttachment(MAIL_ENVELOPE, env, State.Transaction);
    }

    private void stopCapturingData() {
      pipeline.remove(this);
      writeResponse(
        new SMTPResponse(
          "250",
          String.format("Message OK, %d octets received", currentChunkSize)
        )
      );

      if (isLastChunk) {
        callMessageHooks();
      }
    }

    private void writeResponse(Response response) {
      StringBuilder builder = new StringBuilder();
      for (CharSequence line : response.getLines()) {
        builder.append(line).append("\r\n");
      }

      channel.writeAndFlush(
        Unpooled.wrappedBuffer(builder.toString().getBytes(StandardCharsets.US_ASCII))
      );
    }

    private void callMessageHooks() {
      MailEnvelopeImpl env = getMailEnvelope();

      try {
        OutputStream messageOutputStream = env.getMessageOutputStream();

        messageOutputStream.flush();
        messageOutputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      for (MessageHook hook : messageHandlers) {
        hook.onMessage(session, env);
      }
    }

    private MailEnvelopeImpl getMailEnvelope() {
      return session
        .getAttachment(MAIL_ENVELOPE, State.Transaction)
        .orElseThrow(() -> new RuntimeException("No mail envelope on session"));
    }
  }
}
