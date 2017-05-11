package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

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
  private static final String AUTH_XOAUTH2_MECHANISM = "XOAUTH2";
  private static final String CRLF = "\r\n";

  private final Channel channel;
  private final ResponseHandler responseHandler;
  private final SmtpSessionConfig config;
  private final Executor executor;
  private final Supplier<SSLEngine> sslEngineSupplier;
  private final CompletableFuture<Void> closeFuture;
  private final AtomicInteger chunkedBytesSent = new AtomicInteger(0);

  private volatile boolean requiresRset = false;
  private volatile EhloResponse ehloResponse = EhloResponse.EMPTY;

  SmtpSession(Channel channel, ResponseHandler responseHandler, SmtpSessionConfig config, Executor executor, Supplier<SSLEngine> sslEngineSupplier) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.config = config;
    this.executor = executor;
    this.sslEngineSupplier = sslEngineSupplier;
    this.closeFuture = new CompletableFuture<>();

    this.channel.pipeline().addLast(new ErrorHandler());
  }

  public String getConnectionId() {
    return config.getConnectionId();
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

    return send(new DefaultSmtpRequest(STARTTLS_COMMAND)).thenCompose(startTlsResponse -> {
      if (startTlsResponse.containsError()) {
        return CompletableFuture.completedFuture(startTlsResponse);
      } else {
        return performTlsHandshake(startTlsResponse)
            .thenCompose(ignored -> send(SmtpRequests.ehlo(ehloResponse.getEhloDomain())))
            .thenApply(ignored -> startTlsResponse);
      }
    });
  }

  private CompletionStage<SmtpClientResponse> performTlsHandshake(SmtpClientResponse r) {
    CompletableFuture<SmtpClientResponse> ourFuture = new CompletableFuture<>();

    SslHandler sslHandler = new SslHandler(sslEngineSupplier.get());
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

  public Optional<SSLSession> getSSLSession() {
    return Optional.ofNullable(channel.pipeline().get(SslHandler.class)).map(handler -> handler.engine().getSession());
  }

  public CompletableFuture<SmtpClientResponse> send(String from, String to, MessageContent content) {
    return send(from, Collections.singleton(to), content, Optional.empty());
  }

  public CompletableFuture<SmtpClientResponse> send(String from, String to, MessageContent content, SendInterceptor sendInterceptor) {
    return send(from, Collections.singleton(to), content, Optional.of(sendInterceptor));
  }

  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content) {
    return send(from, recipients, content, Optional.empty());
  }

  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content, SendInterceptor sendInterceptor) {
    return send(from, recipients, content, Optional.of(sendInterceptor));
  }

  private CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    Preconditions.checkNotNull(from);
    Preconditions.checkNotNull(recipients);
    Preconditions.checkArgument(!recipients.isEmpty(), "recipients must be > 0");
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());
    Preconditions.checkNotNull(sequenceInterceptor);

    if (ehloResponse.isSupported(Extension.CHUNKING)) {
      return sendAsChunked(from, recipients, content, sequenceInterceptor);
    }

    if (content.getEncoding() == MessageContentEncoding.SEVEN_BIT) {
      return sendAs7Bit(from, recipients, content, sequenceInterceptor);
    }

    if (ehloResponse.isSupported(Extension.EIGHT_BIT_MIME)) {
      return sendAs8BitMime(from, recipients, content, sequenceInterceptor);
    }

    if (content.get8bitCharacterProportion() == 0) {
      return sendAs7Bit(from, recipients, content, sequenceInterceptor);
    }

    // this message is not 7 bit, but the server only supports 7 bit :(
    return sendAs7Bit(from, recipients, encodeContentAs7Bit(content), sequenceInterceptor);
  }

  private CompletableFuture<SmtpClientResponse> sendAsChunked(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    if (ehloResponse.isSupported(Extension.PIPELINING)) {
      List<Object> objects = Lists.newArrayListWithExpectedSize(3 + recipients.size());
      objects.add(SmtpRequests.mail(from));
      objects.addAll(rpctCommands(recipients));

      Iterator<ByteBuf> chunkIterator = content.getContentChunkIterator(channel.alloc());

      ByteBuf firstChunk = chunkIterator.next();
      objects.add(getBdatRequestWithData(firstChunk, !chunkIterator.hasNext()));

      return beginSequence(sequenceInterceptor, objects.size(), objects.toArray())
          .thenSendInTurn(getBdatIterator(chunkIterator))
          .toResponses();

    } else {
      SendSequence sequence = beginSequence(sequenceInterceptor, 1, SmtpRequests.mail(from));

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

  private CompletableFuture<SmtpClientResponse> sendAs7Bit(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    return sendPipelinedIfPossible(SmtpRequests.mail(from), recipients, SmtpRequests.data(), sequenceInterceptor)
        .thenSend(content.getDotStuffedContent(), EMPTY_LAST_CONTENT)
        .toResponses();
  }

  private CompletableFuture<SmtpClientResponse> sendAs8BitMime(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    return sendPipelinedIfPossible(SmtpRequests.mail(from), recipients, new DefaultSmtpRequest(SmtpCommand.DATA, "BODY=8BITMIME"), sequenceInterceptor)
        .thenSend(content.getDotStuffedContent(), EMPTY_LAST_CONTENT)
        .toResponses();
  }

  private SendSequence sendPipelinedIfPossible(SmtpRequest mailRequest, Collection<String> recipients, SmtpRequest dataRequest, Optional<SendInterceptor> sequenceInterceptor) {
    List<SmtpRequest> requests = Lists.newArrayListWithExpectedSize(2 + recipients.size());
    requests.add(mailRequest);
    requests.addAll(rpctCommands(recipients));
    requests.add(dataRequest);

    if (ehloResponse.isSupported(Extension.PIPELINING)) {
      return beginSequence(sequenceInterceptor, requests.size(), requests.toArray());
    } else {
      SendSequence s = beginSequence(sequenceInterceptor, 1, requests.get(0));

      for (int i = 1; i < requests.size(); i++) {
        s.thenSend(requests.get(i));
      }

      return s;
    }
  }

  private Collection<SmtpRequest> rpctCommands(Collection<String> recipients) {
    return recipients.stream().map(SmtpRequests::rcpt).collect(Collectors.toList());
  }

  private SendSequence beginSequence(Optional<SendInterceptor> sequenceInterceptor, int expectedResponses, Object... objects) {
    if (requiresRset) {
      if (ehloResponse.isSupported(Extension.PIPELINING)) {
        return new SendSequence(sequenceInterceptor, expectedResponses + 1, ObjectArrays.concat(SmtpRequests.rset(), objects));
      } else {
        return new SendSequence(sequenceInterceptor, 1,  SmtpRequests.rset()).thenSend(objects);
      }
    } else {
      requiresRset = true;
      return new SendSequence(sequenceInterceptor, expectedResponses, objects);
    }
  }

  private MessageContent encodeContentAs7Bit(MessageContent content) {
    // todo: implement rewriting 8 bit mails as 7 bit
    return content;
  }

  public CompletableFuture<SmtpClientResponse> send(SmtpRequest request) {
    Preconditions.checkNotNull(request);

    return applyOnExecutor(executeRequestInterceptor(config.getSendInterceptor(), request, () -> {
      CompletableFuture<List<SmtpResponse>> responseFuture = responseHandler.createResponseFuture(1, () -> createDebugString(request));
      writeAndFlush(request);

      if (request.command().equals(SmtpCommand.EHLO)) {
        responseFuture = responseFuture.whenComplete((responses, ignored) -> {
          if (responses != null) {
            String ehloDomain = request.parameters().isEmpty() ? "" : request.parameters().get(0).toString();
            parseEhloResponse(ehloDomain, responses.get(0).details());
          }
        });
      }

      return responseFuture;
    }), this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> send(MessageContent content) {
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());

    return applyOnExecutor(executeDataInterceptor(config.getSendInterceptor(), () -> {
      CompletableFuture<List<SmtpResponse>> responseFuture = responseHandler.createResponseFuture(1, () -> "message contents");

      writeContent(content);
      channel.flush();

      return responseFuture;
    }), this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> sendChunk(ByteBuf data, boolean isLast) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.CHUNKING), "Chunking is not supported on this server");
    Preconditions.checkNotNull(data);
    checkMessageSize(OptionalInt.of(chunkedBytesSent.addAndGet(data.readableBytes())));

    if (isLast) {
      // reset the counter so we can send another mail over this connection
      chunkedBytesSent.set(0);
    }

    return applyOnExecutor(executeDataInterceptor(config.getSendInterceptor(), () -> {
      CompletableFuture<List<SmtpResponse>> responseFuture = responseHandler.createResponseFuture(1, () -> "BDAT message chunk");

      String size = Integer.toString(data.readableBytes());
      if (isLast) {
        write(new DefaultSmtpRequest(BDAT_COMMAND, size, "LAST"));
      } else {
        write(new DefaultSmtpRequest(BDAT_COMMAND, size));
      }

      write(data);
      channel.flush();

      return responseFuture;
    }), this::wrapFirstResponse);
  }

  public CompletableFuture<SmtpClientResponse> sendPipelined(SmtpRequest... requests) {
    return sendPipelined(null, requests);
  }

  public CompletableFuture<SmtpClientResponse> sendPipelined(MessageContent content, SmtpRequest... requests) {
    Preconditions.checkState(ehloResponse.isSupported(Extension.PIPELINING), "Pipelining is not supported on this server");
    Preconditions.checkNotNull(requests);
    checkValidPipelinedRequest(requests);
    checkMessageSize(content == null ? OptionalInt.empty() : content.size());

    return applyOnExecutor(executePipelineInterceptor(config.getSendInterceptor(), Lists.newArrayList(requests), () -> {
      int expectedResponses = requests.length + (content == null ? 0 : 1);
      CompletableFuture<List<SmtpResponse>> responseFuture = responseHandler.createResponseFuture(expectedResponses, () -> createDebugString((Object[]) requests));

      if (content != null) {
        writeContent(content);
      }
      for (SmtpRequest r : requests) {
        write(r);
      }

      channel.flush();

      return responseFuture;
    }), this::wrapResponses);
  }

  private SmtpClientResponse wrapResponses(List<SmtpResponse> responses) {
    return new SmtpClientResponse(this, responses);
  }

  private SmtpClientResponse wrapFirstResponse(List<SmtpResponse> responses) {
    return new SmtpClientResponse(this, responses.get(0));
  }

  public CompletableFuture<SmtpClientResponse> authPlain(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthPlainSupported(), "Auth plain is not supported on this server");

    String s = String.format("%s\0%s\0%s", username, username, password);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_PLAIN_MECHANISM, encodeBase64(s)));
  }

  public CompletableFuture<SmtpClientResponse> authXoauth2(String username, String accessToken) {
    Preconditions.checkState(ehloResponse.isAuthXoauth2Supported(), "Auth xoauth2 is not supported on this server");

    String s = String.format("user=%s\001auth=Bearer %s\001\001", username, accessToken);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_XOAUTH2_MECHANISM, encodeBase64(s)));
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
    return applyOnExecutor(executeRequestInterceptor(config.getSendInterceptor(), new DefaultSmtpRequest(AUTH_COMMAND), () -> {
      CompletableFuture<List<SmtpResponse>> responseFuture = responseHandler.createResponseFuture(1, () -> "auth login password");

      String passwordResponse = encodeBase64(password) + CRLF;
      ByteBuf passwordBuffer = channel.alloc().buffer().writeBytes(passwordResponse.getBytes(StandardCharsets.UTF_8));
      writeAndFlush(passwordBuffer);

      return responseFuture;
    }), this::wrapFirstResponse);
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
  void parseEhloResponse(String ehloDomain, Iterable<CharSequence> response) {
    ehloResponse = EhloResponse.parse(ehloDomain, response, config.getDisabledExtensions());
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
    if (executor == SmtpSessionFactoryConfig.DIRECT_EXECUTOR) {
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

  CompletableFuture<List<SmtpResponse>> executeRequestInterceptor(Optional<SendInterceptor> interceptor, SmtpRequest request, Supplier<CompletableFuture<List<SmtpResponse>>> supplier) {
    return interceptor.map(h -> h.aroundRequest(request, supplier)).orElseGet(supplier);
  }

  CompletableFuture<List<SmtpResponse>> executeDataInterceptor(Optional<SendInterceptor> interceptor, Supplier<CompletableFuture<List<SmtpResponse>>> supplier) {
    return interceptor.map(h -> h.aroundData(supplier)).orElseGet(supplier);
  }

  CompletableFuture<List<SmtpResponse>> executePipelineInterceptor(Optional<SendInterceptor> interceptor, List<SmtpRequest> requests, Supplier<CompletableFuture<List<SmtpResponse>>> supplier) {
    return interceptor.map(h -> h.aroundPipelinedSequence(requests, supplier)).orElseGet(supplier);
  }

  private class SendSequence {
    final Optional<SendInterceptor> sequenceInterceptor;
    CompletableFuture<List<SmtpResponse>> responseFuture;

    SendSequence(Optional<SendInterceptor> sequenceInterceptor, int expectedResponses, Object... objects) {
      this.sequenceInterceptor = sequenceInterceptor;
      responseFuture = writeObjectsAndCollectResponses(expectedResponses, objects);
    }

    SendSequence thenSend(Object... objects) {
      responseFuture = responseFuture.thenCompose(responses -> {
        if (SmtpResponses.isError(responses.get(responses.size() - 1))) {
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

    private CompletableFuture<List<SmtpResponse>> sendNext(CompletableFuture<List<SmtpResponse>> prevFuture, Iterator<Object> iterator) {
      if (!iterator.hasNext()) {
        return prevFuture;
      }

      return prevFuture.thenCompose(responses -> {
        if (SmtpResponses.isError(responses.get(responses.size() - 1))) {
          return CompletableFuture.completedFuture(responses);
        }

        Object nextObject = iterator.next();

        CompletableFuture<List<SmtpResponse>> f = writeObjectsAndCollectResponses(1, nextObject)
            .thenApply(mergeResponses(responses));

        return sendNext(f, iterator);
      });
    }

    private Function<List<SmtpResponse>, List<SmtpResponse>> mergeResponses(List<SmtpResponse> existingResponses) {
      return newResponses -> {
        List<SmtpResponse> newList = Lists.newArrayList(existingResponses);
        newList.addAll(newResponses);
        return newList;
      };
    }

    private CompletableFuture<List<SmtpResponse>> writeObjectsAndCollectResponses(int expectedResponses, Object... objects) {
      return executeInterceptor(expectedResponses, objects, () -> {
        CompletableFuture<List<SmtpResponse>> nextFuture = createFuture(expectedResponses, objects);
        writeObjects(objects);
        return nextFuture;
      });
    }

    private CompletableFuture<List<SmtpResponse>> executeInterceptor(int expectedResponses, Object[] objects, Supplier<CompletableFuture<List<SmtpResponse>>> supplier) {
      Optional<SendInterceptor> interceptor = Optional.ofNullable(sequenceInterceptor.orElse(config.getSendInterceptor().orElse(null)));
      if (!interceptor.isPresent()) {
        return supplier.get();
      }

      if (expectedResponses > 1) {
        ArrayList<SmtpRequest> requests = Lists.newArrayList();
        for (Object obj : objects) {
          if (obj instanceof SmtpRequest) {
            requests.add((SmtpRequest) obj);
          }
        }

        return executePipelineInterceptor(interceptor, requests, supplier);
      } else if (objects[0] instanceof SmtpRequest) {
        return executeRequestInterceptor(interceptor, ((SmtpRequest) objects[0]), supplier);
      } else {
        return executeDataInterceptor(interceptor, supplier);
      }
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

    private CompletableFuture<List<SmtpResponse>> createFuture(int expectedResponses, Object[] objects) {
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
