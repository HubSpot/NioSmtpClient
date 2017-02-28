package com.hubspot.smtp.client;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import io.netty.channel.Channel;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;

public class SmtpSession {
  // https://tools.ietf.org/html/rfc2920#section-3.1
  // In particular, the commands RSET, MAIL FROM, SEND FROM, SOML FROM, SAML FROM,
  // and RCPT TO can all appear anywhere in a pipelined command group.
  private static final Set<SmtpCommand> VALID_ANYWHERE_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET, SmtpCommand.MAIL, SmtpCommand.RCPT);

  // https://tools.ietf.org/html/rfc2920#section-3.1
  // The EHLO, DATA, VRFY, EXPN, TURN, QUIT, and NOOP commands can only appear
  // as the last command in a group since their success or failure produces
  // a change of state which the client SMTP must accommodate.
  private static final Set<SmtpCommand> VALID_AT_END_PIPELINED_COMMANDS = Sets.newHashSet(
      SmtpCommand.RSET,
      SmtpCommand.MAIL,
      SmtpCommand.RCPT,
      SmtpCommand.EHLO,
      SmtpCommand.DATA,
      SmtpCommand.VRFY,
      SmtpCommand.EXPN,
      SmtpCommand.QUIT,
      SmtpCommand.NOOP);

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
    Preconditions.checkNotNull(request);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1);
    channel.writeAndFlush(request);

    return responseFuture.thenApply(r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(SmtpRequest... requests) {
    Preconditions.checkNotNull(requests);

    return sendPipelined(new SmtpContent[0], requests);
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(SmtpContent[] contents, SmtpRequest... requests) {
    Preconditions.checkNotNull(contents);
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);

    int expectedResponses = requests.length + (contents.length > 0 ? 1 : 0);
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(expectedResponses);

    for (SmtpContent c : contents) {
      channel.write(c);
    }
    for (SmtpRequest r : requests) {
      channel.write(r);
    }

    channel.flush();

    return responseFuture.thenApply(rs -> {
      SmtpClientResponse[] smtpClientResponses = new SmtpClientResponse[rs.length];
      for (int i = 0; i < smtpClientResponses.length; i++) {
        smtpClientResponses[i] = new SmtpClientResponse(rs[i], this);
      }
      return smtpClientResponses;
    });
  }

  private static void checkValidPipelinedRequest(SmtpRequest[] requests) {
    Preconditions.checkArgument(requests.length > 0, "You must provide requests to pipeline");

    for (int i = 0; i < requests.length; i++) {
      SmtpCommand command = requests[i].command();
      boolean isLastRequest = (i == requests.length - 1);

      if (isLastRequest) {
        Preconditions.checkArgument(VALID_AT_END_PIPELINED_COMMANDS.contains(command),
            command.name() + " cannot be used in a pipelined request");
      } else {
        String errorMessage = VALID_AT_END_PIPELINED_COMMANDS.contains(command) ?
            " must appear last in a pipelined request" : " cannot be used in a pipelined request";

        Preconditions.checkArgument(VALID_ANYWHERE_PIPELINED_COMMANDS.contains(command),
            command.name() + errorMessage);
      }
    }
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpContent... contents) {
    Preconditions.checkNotNull(contents);
    Preconditions.checkArgument(contents.length > 0, "You must provide content to send");

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1);

    for (SmtpContent c : contents) {
      channel.write(c);
    }

    channel.flush();

    return responseFuture.thenApply(r -> new SmtpClientResponse(r[0], this));
  }

  public void setSupportedExtensions(EnumSet<SupportedExtensions> supportedExtensions) {
    this.supportedExtensions = supportedExtensions;
  }

  public boolean isSupported(SupportedExtensions ext) {
    return supportedExtensions.contains(ext);
  }
}
