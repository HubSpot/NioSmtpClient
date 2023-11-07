package com.hubspot.smtp.client;

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
import com.google.common.base.CharMatcher;
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

/**
 * An open connection to an SMTP server which can be used to send messages and other commands.
 *
 * <p>{@link SmtpSession} instances can be created with {@link SmtpSessionFactory#connect(SmtpSessionConfig)}.
 *
 * <p>This class is thread-safe.
 */
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

  private List<String> localExtensionsList;

  SmtpSession(Channel channel, ResponseHandler responseHandler, SmtpSessionConfig config, Executor executor, Supplier<SSLEngine> sslEngineSupplier) {
    this.channel = channel;
    this.responseHandler = responseHandler;
    this.config = config;
    this.executor = executor;
    this.sslEngineSupplier = sslEngineSupplier;
    this.closeFuture = new CompletableFuture<>();

    this.channel.pipeline().addLast(new ErrorHandler());
  }

  /**
   * Returns the {@code connectionId} defined by the {@link SmtpSessionConfig} used when connecting to this server.
   */
  public String getConnectionId() {
    return config.getConnectionId();
  }

  /**
   * Returns a {@code CompletableFuture} that will be completed when this session is closed.
   */
  public CompletableFuture<Void> getCloseFuture() {
    return closeFuture;
  }

  /**
   * Returns an {@link EhloResponse} representing the capabilities of the connection server.
   *
   * <p>If the EHLO command has not been sent, or the response could not be parsed, this
   * method will return an empty {@code EhloResponse} that assumes no extended SMTP features
   * are available.
   */
  public EhloResponse getEhloResponse() {
    return ehloResponse;
  }

  /**
   * Closes this session.
   *
   * @return a {@code CompletableFuture} that will be completed when the session is closed.
   */
  public CompletableFuture<Void> close() {
    this.channel.close();
    return closeFuture;
  }

  /**
   * Sends the STARTTLS command and tries to use TLS for this session.
   *
   * <p>If the server returns an error response (with {@code code >= 400}), this method
   * will return a {@code CompletableFuture<SmtpClientResponse>} with that error response
   * and TLS will not be active.
   *
   * <p>If the TLS handshake fails, this method will return a {@code CompletableFuture<SmtpClientResponse>}
   * that contains the exception thrown by the handshake process.
   *
   * <p>Once the TLS handshake has completed, the EHLO command will be sent again in accordance
   * with the spec. The EHLO response will be parsed and available from {@link SmtpSession#getEhloResponse()}.
   *
   * @return a future containing the response to the STARTTLS command
   * @throws IllegalStateException if TLS is already active
   */
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

  /**
   * Returns true if TLS is active on the session.
   */
  public boolean isEncrypted() {
    return channel.pipeline().get(SslHandler.class) != null;
  }

  /**
   * Returns the {@link SSLSession} used by the current session, or {@link Optional#empty()} if the session
   * is not using TLS.
   */
  public Optional<SSLSession> getSSLSession() {
    return Optional.ofNullable(channel.pipeline().get(SslHandler.class)).map(handler -> handler.engine().getSession());
  }

  /**
   * Sends an email as efficiently as possible, using the extended SMTP features supported by the remote server.
   *
   * This method behaves as {@link SmtpSession#send(String, Collection, MessageContent, SendInterceptor)} but
   * only specifies one recipient and does not use a {@link SendInterceptor}.
   */
  public CompletableFuture<SmtpClientResponse> send(String from, String to, MessageContent content) {
    return send(from, Collections.singleton(to), content, Optional.empty());
  }

  /**
   * Sends an email as efficiently as possible, using the extended SMTP features supported by the remote server.
   *
   * This method behaves as {@link SmtpSession#send(String, Collection, MessageContent, SendInterceptor)} but
   * only specifies one recipient.
   */
  public CompletableFuture<SmtpClientResponse> send(String from, String to, MessageContent content, SendInterceptor sendInterceptor) {
    return send(from, Collections.singleton(to), content, Optional.of(sendInterceptor));
  }

  /**
   * Sends an email as efficiently as possible, using the extended SMTP features supported by the remote server.
   *
   * This method behaves as {@link SmtpSession#send(String, Collection, MessageContent, SendInterceptor)} but
   * does not use a {@link SendInterceptor}.
   */
  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content) {
    return send(from, recipients, content, Optional.empty());
  }

  /**
   * Sends an email as efficiently as possible, using the extended SMTP features supported by the remote server.
   *
   * This method behaves as {@link SmtpSession#send(String, Collection, MessageContent, SendInterceptor)} but
   * does not use a {@link SendInterceptor} and has the capability to accept local mail extensions that can be sent along
   * with mail from command according to RFC 5321. All these local extensions must start with X.
   */
  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content, List<String> localExtensionsList) {
    this.localExtensionsList = localExtensionsList;
    return send(from, recipients, content, Optional.empty());
  }

  /**
   * Sends an email as efficiently as possible, using the extended SMTP features supported by the remote server.
   *
   * <p>This method sends multiple commands to the remote server, and may use pipelining and other features
   * if supported. If any command receives an error response (i.e. with {@code code >= 400}),
   * the sequence of commands is aborted.
   *
   * <p>Because this method adapts to the capabilities of the server, it may send different sequences of commands
   * for the same inputs. For example, communication with a server without extended SMTP support might look as
   * follows:
   *
   * <pre>{@code
   *  CLIENT: MAIL FROM:<from@example.com>
   *  SERVER: 250 OK
   *  CLIENT: RCPT TO:<to@example.com>
   *  SERVER: 250 OK
   *  CLIENT: DATA
   *  SERVER: 354 Start mail input; end with <CRLF>.
   *  CLIENT: <message data>}</pre>
   *
   * <p>Communication with a modern server that supports pipelining and chunking might look different:
   *
   * <pre>{@code
   *  CLIENT: MAIL FROM:<from@example.com>
   *  CLIENT: RCPT TO:<to@example.com>
   *  CLIENT: BDAT 7287 LAST
   *  CLIENT: <binary message data>
   *  SERVER: 250 OK
   *  SERVER: 250 OK
   *  SERVER: 250 OK}</pre>
   *
   * <p>This command assumes the EHLO command has been sent and its result has been parsed.
   *
   * <p>Dot-stuffing will be performed automatically unless SMTP chunking (which does not require dot-stuffing)
   * is available.
   *
   * @param  from the sender of the message, surrounded by < and >, e.g. {@code "<alice@example.com>"}
   * @param  recipients a list of the intended recipients, each surrounded by < and >, e.g. {@code ["<bob@example.com>", "<carol@example.com>"]}
   * @param  content a {@link MessageContent} with the contents of the message
   * @param  sendInterceptor a {@link SendInterceptor} which will be called before commands and data are sent
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain each of the responses received
   *         from the remote server, or an exception if the send failed unexpectedly.
   * @throws NullPointerException if any of the arguments are null
   * @throws IllegalArgumentException if {@code recipients} is empty
   * @throws MessageTooLargeException if the EHLO response indicated a maximum message size that would be
   *         exceeded by {@code content}. Note if {@link MessageContent#size()} is not specified this check
   *         is not performed.
   */
  public CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content, SendInterceptor sendInterceptor) {
    return send(from, recipients, content, Optional.of(sendInterceptor));
  }

  private CompletableFuture<SmtpClientResponse> send(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    return sendInternal(from, recipients, content, sequenceInterceptor);
  }

  private CompletableFuture<SmtpClientResponse> sendInternal(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    Preconditions.checkNotNull(from);
    Preconditions.checkNotNull(recipients);
    Preconditions.checkArgument(!recipients.isEmpty(), "recipients must be > 0");
    Preconditions.checkNotNull(content);
    checkMessageSize(content.size());
    Preconditions.checkNotNull(sequenceInterceptor);

    if (ehloResponse.isSupported(Extension.CHUNKING)) {
      return sendAsChunked(from, recipients, content, sequenceInterceptor);
    }

    if (content.getEncoding() != MessageContentEncoding.SEVEN_BIT && !ehloResponse.isSupported(Extension.EIGHT_BIT_MIME)) {
      content = encodeContentAs7Bit(content);
    }

    return sendMessage(from, recipients, content, sequenceInterceptor);
  }

  private CompletableFuture<SmtpClientResponse> sendAsChunked(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    if (ehloResponse.isSupported(Extension.PIPELINING)) {
      List<Object> objects = Lists.newArrayListWithExpectedSize(3 + recipients.size());
      objects.add(mailCommand(from, recipients));
      objects.addAll(rpctCommands(recipients));

      Iterator<ByteBuf> chunkIterator = content.getContentChunkIterator(channel.alloc());

      ByteBuf firstChunk = chunkIterator.next();
      if (firstChunk == null) {
        throw new IllegalArgumentException("The MessageContent was empty; size is " +
            (content.size().isPresent() ? Integer.toString(content.size().getAsInt()) : "not present"));
      }

      objects.add(getBdatRequestWithData(firstChunk, !chunkIterator.hasNext()));

      return beginSequence(sequenceInterceptor, objects.size(), objects.toArray())
          .thenSendInTurn(getBdatIterator(chunkIterator))
          .toResponses();

    } else {
      SendSequence sequence = beginSequence(sequenceInterceptor, 1, mailCommand(from, recipients));

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

  private CompletableFuture<SmtpClientResponse> sendMessage(String from, Collection<String> recipients, MessageContent content, Optional<SendInterceptor> sequenceInterceptor) {
    return sendPipelinedIfPossible(mailCommand(from, recipients), recipients, SmtpRequests.data(), sequenceInterceptor)
            .thenSend(content.getDotStuffedContent(), DotCrlfBuffer.get())
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

  private SmtpRequest mailCommand(String from, Collection<String> recipients){
    if(localExtensionsList == null){
      localExtensionsList = new ArrayList<>();
    }
    if (ehloResponse.isSupported(Extension.EIGHT_BIT_MIME)) {
      localExtensionsList.add("BODY=8BITMIME");
    }
    if (ehloResponse.isSupported(Extension.SMTPUTF8) && !(isAllAscii(from) && isAllAscii(recipients))) {
      localExtensionsList.add("SMTPUTF8");
    }

    return SmtpRequests.mail(from, localExtensionsList.toArray(new String[0]));
  }

  private static boolean isAllAscii(String s) {
    return CharMatcher.ascii().matchesAllOf(s);
  }

  private static boolean isAllAscii(Collection<String> strings) {
    return strings.stream().allMatch(SmtpSession::isAllAscii);
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

  /**
   * Sends a command to the remote server and waits for a response.
   *
   * <p>If the command is EHLO, the server's response will be parsed and available from
   * {@link SmtpSession#getEhloResponse()}.
   *
   * @param  request the command and arguments to send
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly.
   * @throws NullPointerException if request is null
   */
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

  /**
   * Sends message content to the remote server and waits for a response.
   *
   * <p>This method should be called when the server is in the "waiting for data" state,
   * i.e. when the DATA command has been sent and the server has returned a 354 response.
   *
   * @param  content the message contents to send
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly.
   * @throws NullPointerException if content is null
   * @throws MessageTooLargeException if the EHLO response indicated a maximum message size that would be
   *         exceeded by {@code content}. Note if {@link MessageContent#size()} is not specified this check
   *         is not performed.
   */
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

  /**
   * Sends binary message content to the remote server and waits for a response.
   *
   * <p>This method sends a BDAT command, immediately followed by the message contents,
   * and depends on server support for SMTP chunking.
   *
   * @param  data the message contents to send
   * @param  isLast whether this is the last message chunk, and should included the LAST keyword
   *                with the BDAT command.
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly.
   * @throws NullPointerException if data is null
   * @throws IllegalStateException if the server does not support chunking, or if the EHLO response has
   *         not been received.
   * @throws MessageTooLargeException if the EHLO response indicated a maximum message size that would be
   *         exceeded by {@code content}. Note if {@link MessageContent#size()} is not specified this check
   *         is not performed.
   */
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

  /**
   * Sends a series of commands to the server without waiting for a response.
   *
   * <p>This is identical to {@link SmtpSession#sendPipelined(MessageContent, SmtpRequest...)} but it
   * does not send any message content.
   */
  public CompletableFuture<SmtpClientResponse> sendPipelined(SmtpRequest... requests) {
    return sendPipelined(null, requests);
  }

  /**
   * Sends a series of commands to the server without waiting for a response.
   *
   * <p>This method reduces latency by sending all the specified commands without waiting
   * for the server to respond to each one in turn. It depends on server support for SMTP pipelining.
   *
   * <p>The server will send a response for each command included in the pipelined sequence. These are
   * available from {@link SmtpClientResponse#getResponses()}.
   *
   * <p>According to the pipelining spec, the contents of one message can be sent together with
   * a RSET and metadata for a <i>subsequent</i> message. As such, the {@code content} and {@code requests}
   * arguments to this method refer to different e-mails.
   *
   * @param  content the content to send
   * @param  requests the commands to send
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the responses received
   *         from the remote server, or an exception if the send failed unexpectedly
   * @throws NullPointerException if requests is null
   * @throws IllegalArgumentException if the sequence of commands in {@code requests} is not valid
   *         according to the pipelining spec
   * @throws IllegalStateException if the server does not support pipelining, or if the EHLO response has
   *         not been received
   * @throws MessageTooLargeException if the EHLO response indicated a maximum message size that would be
   *         exceeded by {@code content}. Note if {@link MessageContent#size()} is not specified this check
   *         is not performed
   */
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

  /**
   * Authenticates with the remote server using the PLAIN mechanism.
   *
   * @param  username the plaintext username used to authenticate
   * @param  password the plaintext password used to authenticate
   * @throws IllegalStateException if the server does not support PLAIN authentication,
   *         or if the EHLO response has not been received
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly
   */
  public CompletableFuture<SmtpClientResponse> authPlain(String username, String password) {
    Preconditions.checkState(ehloResponse.isAuthPlainSupported(), "Auth plain is not supported on this server");

    String s = String.format("%s\0%s\0%s", username, username, password);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_PLAIN_MECHANISM, encodeBase64(s)));
  }


  /**
   * Authenticates with the remote server using the XOAUTH2 mechanism.
   *
   * @param  username the plaintext username used to authenticate
   * @param  accessToken the access token used to authenticate
   * @throws IllegalStateException if the server does not support XOAUTH2 authentication,
   *         or if the EHLO response has not been received
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly
   */
  public CompletableFuture<SmtpClientResponse> authXoauth2(String username, String accessToken) {
    Preconditions.checkState(ehloResponse.isAuthXoauth2Supported(), "Auth xoauth2 is not supported on this server");

    String s = String.format("user=%s\001auth=Bearer %s\001\001", username, accessToken);
    return send(new DefaultSmtpRequest(AUTH_COMMAND, AUTH_XOAUTH2_MECHANISM, encodeBase64(s)));
  }

  /**
   * Authenticates with the remote server using the LOGIN mechanism.
   *
   * <p>This method will attempt to send two commands to the server: the first includes the
   * username, the second specifies the password. The returned {@code SmtpClientResponse}
   * will contain the response to just one command - the first if the username was
   * rejected by the server, or the second if the username was accepted and the password
   * was sent.
   *
   * @param  username the plaintext username used to authenticate
   * @param  password the plaintext password used to authenticate
   * @throws IllegalStateException if the server does not support LOGIN authentication,
   *         or if the EHLO response has not been received
   * @return a {@code CompletableFuture<SmtpClientResponse>} that will contain the response received
   *         from the remote server, or an exception if the send failed unexpectedly
   */
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

    long maximumSize = ehloResponse.getMaxMessageSize().get();
    if (maximumSize < size.getAsInt()) {
      throw new MessageTooLargeException(config.getConnectionId(), maximumSize);
    }
  }

  private String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private void writeContent(MessageContent content) {
    write(content.getDotStuffedContent());
    write(DotCrlfBuffer.get());
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
