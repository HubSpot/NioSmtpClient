package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.utils.SmtpResponses;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;

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

  private static final Supplier<Executor> SHARED_DEFAULT_EXECUTOR = Suppliers.memoize(SmtpSession::getSharedExecutor);

  private static ExecutorService getSharedExecutor() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).setNameFormat("niosmtpclient-%d").build();
    return Executors.newCachedThreadPool(threadFactory);
  }

  private static final Joiner COMMA_JOINER = Joiner.on(", ");
  private static final SmtpCommand STARTTLS_COMMAND = SmtpCommand.valueOf("STARTTLS");
  private static final SmtpCommand AUTH_COMMAND = SmtpCommand.valueOf("AUTH");
  private static final SmtpCommand BDAT_COMMAND = SmtpCommand.valueOf("BDAT");
  private static final String AUTH_PLAIN_MECHANISM = "PLAIN";
  private static final String AUTH_LOGIN_MECHANISM = "LOGIN";
  private static final String CRLF = "\r\n";

  private final Channel channel;
  private final ResponseHandler responseHandler;
  private final SmtpSessionConfig config;
  private final Executor executor;
  private final CompletableFuture<Void> closeFuture;
  private final AtomicInteger chunkedBytesSent = new AtomicInteger(0);

  private volatile EhloResponse ehloResponse = EhloResponse.EMPTY;

  SmtpSession(Channel channel, ResponseHandler responseHandler, SmtpSessionConfig config) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.config = config;
    this.executor = config.getExecutor().orElse(SHARED_DEFAULT_EXECUTOR.get());
    this.closeFuture = new CompletableFuture<>();

    this.channel.pipeline().addLast(new ErrorHandler());
  }

  public CompletableFuture<Void> getCloseFuture() {
    return closeFuture;
  }

  public EhloResponse getEhloResponse() {
    return ehloResponse;
  }

  public CompletableFuture<Void> close() {
    this.channel.close();
    return closeFuture;
  }

  public CompletableFuture<SmtpClientResponse> startTls() {
    Preconditions.checkState(!isEncrypted(), "This connection is already using TLS");

    return send(new DefaultSmtpRequest(STARTTLS_COMMAND)).thenCompose(r -> {
      if (SmtpResponses.isError(r)) {
        return CompletableFuture.completedFuture(r);
      } else {
        return performTlsHandshake(r);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> performTlsHandshake(SmtpClientResponse r) {
    CompletableFuture<SmtpClientResponse> ourFuture = new CompletableFuture<>();

    SslHandler sslHandler = new SslHandler(config.getSSLEngineSupplier().get());
    channel.pipeline().addFirst(sslHandler);

    sslHandler.handshakeFuture().addListener(nettyFuture -> {
      if (nettyFuture.isSuccess()) {
        ourFuture.complete(r);
      } else {
        ourFuture.completeExceptionally(nettyFuture.cause());
        close();
      }
    });

    return ourFuture;
  }

  public boolean isEncrypted() {
    return channel.pipeline().get(SslHandler.class) != null;
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpRequest request) {
    Preconditions.checkNotNull(request);

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> createDebugString(request));
    writeAndFlush(request);

    if (request.command().equals(SmtpCommand.EHLO)) {
      responseFuture = responseFuture.whenComplete((response, ignored) -> {
        if (response != null) {
          parseEhloResponse(response[0].details());
        }
      });
    }

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse> send(MessageContent content) {
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "message contents");

    writeContent(content);
    channel.flush();

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse> sendChunk(ByteBuf data, boolean isLast) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.CHUNKING), "Chunking is not supported on this server");
    Preconditions.checkNotNull(data);
    checkMessageSize(chunkedBytesSent.addAndGet(data.readableBytes()));

    if (isLast) {
      // reset the counter so we can send another mail over this connection
      chunkedBytesSent.set(0);
    }

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "BDAT message chunk");

    String size = Integer.toString(data.readableBytes());
    if (isLast) {
      write(new DefaultSmtpRequest(BDAT_COMMAND, size, "LAST"));
    } else {
      write(new DefaultSmtpRequest(BDAT_COMMAND, size));
    }

    write(data);
    channel.flush();

    return applyOnExecutor(responseFuture, r -> new SmtpClientResponse(r[0], this));
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(SmtpRequest... requests) {
    Preconditions.checkNotNull(requests);

    return sendPipelined(null, requests);
  }

  public CompletableFuture<SmtpClientResponse[]> sendPipelined(MessageContent content, SmtpRequest... requests) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.PIPELINING), "Pipelining is not supported on this server");
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);
    checkMessageSize(content == null ? 0 : content.size());

    int expectedResponses = requests.length + (content == null ? 0 : 1);
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(expectedResponses, () -> createDebugString(requests));

    if (content != null) {
      writeContent(content);
    }
    for (SmtpRequest r : requests) {
      write(r);
    }

    channel.flush();

    return applyOnExecutor(responseFuture, rs -> {
      SmtpClientResponse[] smtpClientResponses = new SmtpClientResponse[rs.length];
      for (int i = 0; i < smtpClientResponses.length; i++) {
        smtpClientResponses[i] = new SmtpClientResponse(rs[i], this);
      }
      return smtpClientResponses;
    });
  }

  public CompletableFuture<SmtpClientResponse> authPlain(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthPlainSupported(), "Auth plain is not supported on this server");

    String s = String.format("%s\0%s\0%s", username, username, password);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_PLAIN_MECHANISM, encodeBase64(s)));
  }

  public CompletableFuture<SmtpClientResponse> authLogin(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthLoginSupported(), "Auth login is not supported on this server");

    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_LOGIN_MECHANISM, encodeBase64(username))).thenCompose(r -> {
      if (SmtpResponses.isError(r)) {
        return CompletableFuture.completedFuture(r);
      } else {
        return sendAuthLoginPassword(password);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> sendAuthLoginPassword(String password) {
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "auth login password");

    String passwordResponse = encodeBase64(password) + CRLF;
    ByteBuf passwordBuffer = channel.alloc().buffer().writeBytes(passwordResponse.getBytes(StandardCharsets.UTF_8));
    writeAndFlush(passwordBuffer);

    return applyOnExecutor(responseFuture, loginResponse -> new SmtpClientResponse(loginResponse[0], this));
  }

  private void checkMessageSize(int size) {
    if (!ehloResponse.getMaxMessageSize().isPresent()) {
      return;
    }

    if (ehloResponse.getMaxMessageSize().get() < size) {
      throw new MessageTooLargeException(config.getConnectionId(), ehloResponse.getMaxMessageSize().get());
    }
  }

  private String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private void writeContent(MessageContent content) {
    write(content.getDotStuffedContent());

    // SmtpRequestEncoder requires that we send an SmtpContent instance after the DATA command
    // to unset its contentExpected state.
    write(EMPTY_LAST_CONTENT);
  }

  private void write(Object obj) {
    // adding ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE ensures we'll find out
    // about errors that occur when writing
    channel.write(obj).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
  }

  private void writeAndFlush(Object obj) {
    // adding ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE ensures we'll find out
    // about errors that occur when writing
    channel.writeAndFlush(obj).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
  }

  @VisibleForTesting
  void parseEhloResponse(Iterable<CharSequence> response) {
    ehloResponse = EhloResponse.parse(response);
  }

  @VisibleForTesting
  static String createDebugString(SmtpRequest... requests) {
    return COMMA_JOINER.join(Arrays.stream(requests)
        .map(r -> r.command().equals(AUTH_COMMAND) ? "<redacted-auth-command>" : requestToString(r))
        .collect(Collectors.toList()));
  }

  private static  String requestToString(SmtpRequest request) {
    return String.format("%s %s", request.command().name(), Joiner.on(" ").join(request.parameters()));
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

  private <R, T> CompletableFuture<R> applyOnExecutor(CompletableFuture<T> eventLoopFuture, Function<T, R> mapper) {
    if (executor == SmtpSessionConfig.DIRECT_EXECUTOR) {
      return eventLoopFuture.thenApply(mapper);
    }

    // use handleAsync to ensure exceptions and other callbacks are completed on the ExecutorService thread
    return eventLoopFuture.handleAsync((rs, e) -> {
      if (e != null) {
        throw Throwables.propagate(e);
      }

      return mapper.apply(rs);
    }, executor);
  }

  private class ErrorHandler extends ChannelInboundHandlerAdapter {
    private Throwable cause;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      this.cause = cause;
      ctx.close();
    }

    @Override
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      if (cause != null) {
        closeFuture.completeExceptionally(cause);
      } else {
        closeFuture.complete(null);
      }

      super.channelInactive(ctx);
    }
  }
}
