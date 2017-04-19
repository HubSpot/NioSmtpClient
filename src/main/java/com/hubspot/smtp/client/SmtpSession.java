package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.collect.Sets;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.messages.MessageContentEncoding;
import com.hubspot.smtp.utils.SmtpResponses;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpRequests;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedInput;

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

  private volatile boolean requiresRset = false;
  private volatile EhloResponse ehloResponse = EhloResponse.EMPTY;

  SmtpSession(Channel channel, ResponseHandler responseHandler, SmtpSessionConfig config) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.config = config;
    this.executor = config.getEffectiveExecutor();
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
      if (r.containsError()) {
        return CompletableFuture.completedFuture(r);
      } else {
        return performTlsHandshake(r);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> performTlsHandshake(SmtpClientResponse r) {
    CompletableFuture<SmtpClientResponse> ourFuture = new CompletableFuture<>();

    SslHandler sslHandler = new SslHandler(config.getSslEngineSupplier().get());
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

  public CompletableFuture<SmtpClientResponse> send(String from, String to, MessageContent content) {
    return send(from, Collections.singleton(to), content);
  }

  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content) {
    Preconditions.checkNotNull(from);
    Preconditions.checkNotNull(recipients);
    Preconditions.checkArgument(!recipients.isEmpty(), "recipients must be > 0");
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());

    if (ehloResponse.isSupported(Extension.CHUNKING)) {
      return sendAsChunked(from, recipients, content);
    }

    if (content.getEncoding() == MessageContentEncoding.SEVEN_BIT) {
      return sendAs7Bit(from, recipients, content);
    }

    if (ehloResponse.isSupported(Extension.EIGHT_BIT_MIME)) {
      return sendAs8BitMime(from, recipients, content);
    }

    if (content.get8bitCharacterProportion() == 0) {
      return sendAs7Bit(from, recipients, content);
    }

    // this message is not 7 bit, but the server only supports 7 bit :(
    return sendAs7Bit(from, recipients, encodeContentAs7Bit(content));
  }

  private CompletableFuture<SmtpClientResponse> sendAsChunked(String from, Collection<String> recipients, MessageContent content) {
    if (ehloResponse.isSupported(Extension.PIPELINING)) {
      List<Object> objects = Lists.newArrayListWithExpectedSize(3 + recipients.size());
      objects.add(SmtpRequests.mail(from));
      objects.addAll(rpctCommands(recipients));

      Iterator<ByteBuf> chunkIterator = content.getContentChunkIterator(channel.alloc());

      ByteBuf firstChunk = chunkIterator.next();
      objects.add(getBdatRequestWithData(firstChunk, !chunkIterator.hasNext()));

      return beginSequence(objects.size(), objects.toArray())
          .thenSendInTurn(getBdatIterator(chunkIterator))
          .toResponses();

    } else {
      SendSequence sequence = beginSequence(1, SmtpRequests.mail(from));

      for (String recipient : recipients) {
        sequence.thenSend(SmtpRequests.rcpt(recipient));
      }

      return sequence
          .thenSendInTurn(getBdatIterator(content.getContentChunkIterator(channel.alloc())))
          .toResponses();
    }
  }

  @SuppressFBWarnings("VA_FORMAT_STRING_USES_NEWLINE") // we shouldn't use platform-specific newlines for SMTP
  private ByteBuf getBdatRequestWithData(ByteBuf data, boolean isLast) {
    String request = String.format("BDAT %d%s\r\n", data.readableBytes(), isLast ? " LAST" : "");
    ByteBuf requestBuf = channel.alloc().buffer(request.length());
    ByteBufUtil.writeAscii(requestBuf, request);

    return channel.alloc().compositeBuffer().addComponents(true, requestBuf, data);
  }

  private Iterator<Object> getBdatIterator(Iterator<ByteBuf> chunkIterator) {
    return new Iterator<Object>() {
      @Override
      public boolean hasNext() {
        return chunkIterator.hasNext();
      }

      @Override
      public Object next() {
        ByteBuf buf = chunkIterator.next();
        return getBdatRequestWithData(buf, !chunkIterator.hasNext());
      }
    };
  }

  private CompletableFuture<SmtpClientResponse> sendAs7Bit(String from, Collection<String> recipients, MessageContent content) {
    return sendPipelinedIfPossible(SmtpRequests.mail(from), recipients, SmtpRequests.data())
        .thenSend(content.getDotStuffedContent(), EMPTY_LAST_CONTENT)
        .toResponses();
  }

  private CompletableFuture<SmtpClientResponse> sendAs8BitMime(String from, Collection<String> recipients, MessageContent content) {
    return sendPipelinedIfPossible(SmtpRequests.mail(from), recipients, new DefaultSmtpRequest(SmtpCommand.DATA, "BODY=8BITMIME"))
        .thenSend(content.getDotStuffedContent(), EMPTY_LAST_CONTENT)
        .toResponses();
  }

  private SendSequence sendPipelinedIfPossible(SmtpRequest mailRequest, Collection<String> recipients, SmtpRequest dataRequest) {
    List<SmtpRequest> requests = Lists.newArrayListWithExpectedSize(2 + recipients.size());
    requests.add(mailRequest);
    requests.addAll(rpctCommands(recipients));
    requests.add(dataRequest);

    if (ehloResponse.isSupported(Extension.PIPELINING)) {
      return beginSequence(requests.size(), requests.toArray());
    } else {
      SendSequence s = beginSequence(1, requests.get(0));

      for (int i = 1; i < requests.size(); i++) {
        s.thenSend(requests.get(i));
      }

      return s;
    }
  }

  private Collection<SmtpRequest> rpctCommands(Collection<String> recipients) {
    return recipients.stream().map(SmtpRequests::rcpt).collect(Collectors.toList());
  }

  private SendSequence beginSequence(int expectedResponses, Object... objects) {
    if (requiresRset) {
      return new SendSequence(expectedResponses + 1, ObjectArrays.concat(SmtpRequests.rset(), objects));
    } else {
      requiresRset = true;
      return new SendSequence(expectedResponses, objects);
    }
  }

  private MessageContent encodeContentAs7Bit(MessageContent content) {
    // todo: implement rewriting 8 bit mails as 7 bit
    return content;
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

    return applyOnExecutor(responseFuture, this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> send(MessageContent content) {
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());

    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(1, () -> "message contents");

    writeContent(content);
    channel.flush();

    return applyOnExecutor(responseFuture, this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> sendChunk(ByteBuf data, boolean isLast) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.CHUNKING), "Chunking is not supported on this server");
    Preconditions.checkNotNull(data);
    checkMessageSize(OptionalInt.of(chunkedBytesSent.addAndGet(data.readableBytes())));

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

    return applyOnExecutor(responseFuture, this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> sendPipelined(SmtpRequest... requests) {
    return sendPipelined(null, requests);
  }

  public CompletableFuture<SmtpClientResponse> sendPipelined(MessageContent content, SmtpRequest... requests) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.PIPELINING), "Pipelining is not supported on this server");
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);
    checkMessageSize(content == null ? OptionalInt.empty() : content.size());

    int expectedResponses = requests.length + (content == null ? 0 : 1);
    CompletableFuture<SmtpResponse[]> responseFuture = responseHandler.createResponseFuture(expectedResponses, () -> createDebugString((Object[]) requests));

    if (content != null) {
      writeContent(content);
    }
    for (SmtpRequest r : requests) {
      write(r);
    }

    channel.flush();

    return applyOnExecutor(responseFuture, this::wrapResponses);
  }

  private SmtpClientResponse wrapResponses(SmtpResponse[] responses) {
    return new SmtpClientResponse(this, responses);
  }

  private SmtpClientResponse wrapFirstResponse(SmtpResponse[] responses) {
    return new SmtpClientResponse(this, responses[0]);
  }

  public CompletableFuture<SmtpClientResponse> authPlain(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthPlainSupported(), "Auth plain is not supported on this server");

    String s = String.format("%s\0%s\0%s", username, username, password);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_PLAIN_MECHANISM, encodeBase64(s)));
  }

  public CompletableFuture<SmtpClientResponse> authLogin(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthLoginSupported(), "Auth login is not supported on this server");

    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_LOGIN_MECHANISM, encodeBase64(username))).thenCompose(r -> {
      if (r.containsError()) {
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

    return applyOnExecutor(responseFuture, this::wrapFirstResponse);
  }

  private void checkMessageSize(OptionalInt size) {
    if (!ehloResponse.getMaxMessageSize().isPresent() || !size.isPresent()) {
      return;
    }

    if (ehloResponse.getMaxMessageSize().get() < size.getAsInt()) {
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
    ehloResponse = EhloResponse.parse(response, config.getDisabledExtensions());
  }

  @VisibleForTesting
  static String createDebugString(Object... objects) {
    return COMMA_JOINER.join(Arrays.stream(objects).map(SmtpSession::objectToString).collect(Collectors.toList()));
  }

  private static String objectToString(Object o) {
    if (o instanceof SmtpRequest) {
      SmtpRequest request = (SmtpRequest) o;

      if (request.command().equals(AUTH_COMMAND)) {
        return "<redacted-auth-command>";
      } else {
        return String.format("%s %s", request.command().name(), Joiner.on(" ").join(request.parameters()));
      }
    } else if (o instanceof SmtpContent || o instanceof ByteBuf || o instanceof ChunkedInput) {
      return "[CONTENT]";
    } else {
      return o.toString();
    }
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

  private class SendSequence {
    CompletableFuture<SmtpResponse[]> responseFuture;

    SendSequence(int expectedResponses, Object... objects) {
      responseFuture = writeObjectsAndCollectResponses(expectedResponses, objects);
    }

    SendSequence thenSend(Object... objects) {
      responseFuture = responseFuture.thenCompose(responses -> {
        if (SmtpResponses.isError(responses[responses.length - 1])) {
          return CompletableFuture.completedFuture(responses);
        }

        return writeObjectsAndCollectResponses(1, objects)
            .thenApply(mergeResponses(responses));
      });

      return this;
    }

    // sends the next item from the iterator only when the response for the previous one
    // has arrived, continuing until the iterator is empty or the response is an error
    SendSequence thenSendInTurn(Iterator<Object> iterator) {
      responseFuture = sendNext(responseFuture, iterator);
      return this;
    }

    private CompletableFuture<SmtpResponse[]> sendNext(CompletableFuture<SmtpResponse[]> prevFuture, Iterator<Object> iterator) {
      if (!iterator.hasNext()) {
        return prevFuture;
      }

      return prevFuture.thenCompose(responses -> {
        if (SmtpResponses.isError(responses[responses.length - 1])) {
          return CompletableFuture.completedFuture(responses);
        }

        Object nextObject = iterator.next();

        CompletableFuture<SmtpResponse[]> f = writeObjectsAndCollectResponses(1, nextObject)
            .thenApply(mergeResponses(responses));

        return sendNext(f, iterator);
      });
    }

    private Function<SmtpResponse[], SmtpResponse[]> mergeResponses(SmtpResponse[] existingResponses) {
      return newResponses -> ObjectArrays.concat(existingResponses, newResponses, SmtpResponse.class);
    }

    private CompletableFuture<SmtpResponse[]> writeObjectsAndCollectResponses(int expectedResponses, Object... objects) {
      CompletableFuture<SmtpResponse[]> nextFuture = createFuture(expectedResponses, objects);
      writeObjects(objects);
      return nextFuture;
    }

    CompletableFuture<SmtpClientResponse> toResponses() {
      return applyOnExecutor(responseFuture, SmtpSession.this::wrapResponses);
    }

    private void writeObjects(Object[] objects) {
      for (Object obj : objects) {
        write(obj);
      }
      channel.flush();
    }

    private CompletableFuture<SmtpResponse[]> createFuture(int expectedResponses, Object[] objects) {
      return responseHandler.createResponseFuture(expectedResponses, () -> createDebugString(objects));
    }
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
