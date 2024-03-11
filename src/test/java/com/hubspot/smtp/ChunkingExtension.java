package com.hubspot.smtp;

import static com.hubspot.smtp.ExtensibleNettyServer.NETTY_CHANNEL;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.james.protocols.api.ProtocolSession;
import org.apache.james.protocols.api.ProtocolSession.State;
import org.apache.james.protocols.api.Request;
import org.apache.james.protocols.api.Response;
import org.apache.james.protocols.api.future.FutureResponseImpl;
import org.apache.james.protocols.api.handler.CommandHandler;
import org.apache.james.protocols.api.handler.ExtensibleHandler;
import org.apache.james.protocols.api.handler.LineHandler;
import org.apache.james.protocols.api.handler.WiringException;
import org.apache.james.protocols.netty.HandlerConstants;
import org.apache.james.protocols.smtp.MailAddress;
import org.apache.james.protocols.smtp.MailEnvelopeImpl;
import org.apache.james.protocols.smtp.SMTPResponse;
import org.apache.james.protocols.smtp.SMTPRetCode;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.core.esmtp.EhloExtension;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.Hook;
import org.apache.james.protocols.smtp.hook.MessageHook;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;

public class ChunkingExtension
  implements EhloExtension, CommandHandler<SMTPSession>, ExtensibleHandler, Hook {

  private static final String MAIL_ENVELOPE = "mail envelope";
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

    Channel channel = (Channel) session.getAttachment(NETTY_CHANNEL, State.Connection);
    if (channel == null) {
      throw new RuntimeException(
        "ExtensibleNettyServer must be used to support chunking"
      );
    }

    BdatHandler bdatHandler = new BdatHandler(channel.getPipeline(), session);
    bdatHandler.startCapturingData(
      Integer.parseInt(matcher.group("size")),
      !matcher.group("last").isEmpty()
    );
    return bdatHandler.bdatResponseFuture;
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

  private class BdatHandler extends SimpleChannelUpstreamHandler {

    private final ChannelPipeline pipeline;
    private final SMTPSession session;
    private final FutureResponseImpl bdatResponseFuture;

    private int currentChunkSize;
    private int bytesRead;
    private boolean isLastChunk;

    public BdatHandler(ChannelPipeline pipeline, SMTPSession session) {
      this.pipeline = pipeline;
      this.session = session;
      this.bdatResponseFuture = new FutureResponseImpl();
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
      throws Exception {
      if (e.getMessage() instanceof ChannelBuffer) {
        ChannelBuffer buffer = (ChannelBuffer) e.getMessage();

        int bytesToRead = Math.min(currentChunkSize - bytesRead, buffer.readableBytes());
        buffer.readBytes(getMailEnvelope().getMessageOutputStream(), bytesToRead);
        bytesRead += bytesToRead;

        if (bytesRead == currentChunkSize) {
          stopCapturingData();
        }

        return;
      }

      super.messageReceived(ctx, e);
    }

    @SuppressWarnings("unchecked")
    private void startCapturingData(int bytesToCapture, boolean last) {
      currentChunkSize = bytesToCapture;
      isLastChunk = last;

      pipeline.addBefore(HandlerConstants.FRAMER, BDAT_HANDLER_NAME, this);

      MailEnvelopeImpl env = new MailEnvelopeImpl();
      env.setRecipients(
        Lists.newArrayList(
          (Collection<MailAddress>) session.getAttachment(
            SMTPSession.RCPT_LIST,
            State.Transaction
          )
        )
      );
      env.setSender(
        (MailAddress) session.getAttachment(
          SMTPSession.SENDER,
          ProtocolSession.State.Transaction
        )
      );

      session.setAttachment(MAIL_ENVELOPE, env, ProtocolSession.State.Transaction);
    }

    private void stopCapturingData() {
      pipeline.remove(this);
      bdatResponseFuture.setResponse(
        new SMTPResponse(
          "250",
          String.format("Message OK, %d octets received", currentChunkSize)
        )
      );

      if (isLastChunk) {
        callMessageHooks();
      }
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
      return (MailEnvelopeImpl) session.getAttachment(MAIL_ENVELOPE, State.Transaction);
    }
  }
}
