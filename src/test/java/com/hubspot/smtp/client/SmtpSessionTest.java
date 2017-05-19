package com.hubspot.smtp.client;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.net.ssl.SSLEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.messages.MessageContentEncoding;
import com.hubspot.smtp.utils.SmtpResponses;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.DefaultSmtpResponse;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.codec.smtp.SmtpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;

public class SmtpSessionTest {
  private static final String ALICE = "alice@example.com";
  private static final String BOB = "bob@example.com";
  private static final String CAROL = "carol@example.com";
  private static final String MESSAGE_CONTENTS = "Subject: test\r\nhi\r\n";
  private static final byte[] MESSAGE_BYTES = MESSAGE_CONTENTS.getBytes(StandardCharsets.UTF_8);

  private static final SmtpRequest SMTP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpResponse OK_RESPONSE = new DefaultSmtpResponse(250, "OK");
  private static final SmtpResponse FAIL_RESPONSE = new DefaultSmtpResponse(400, "nope");
  private static final SmtpResponse INTERMEDIATE_RESPONSE = new DefaultSmtpResponse(300, "... go on");
  private static final SmtpRequest MAIL_REQUEST = new DefaultSmtpRequest(SmtpCommand.MAIL, "FROM:" + ALICE);
  private static final SmtpRequest RCPT_REQUEST = new DefaultSmtpRequest(SmtpCommand.RCPT, "FROM:" + BOB);
  private static final SmtpRequest DATA_REQUEST = new DefaultSmtpRequest(SmtpCommand.DATA);
  private static final SmtpRequest EHLO_REQUEST = new DefaultSmtpRequest(SmtpCommand.EHLO);
  private static final SmtpRequest NOOP_REQUEST = new DefaultSmtpRequest(SmtpCommand.NOOP);
  private static final SmtpRequest HELO_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELO);
  private static final SmtpRequest HELP_REQUEST = new DefaultSmtpRequest(SmtpCommand.HELP);

  private static final SmtpSessionConfig CONFIG = SmtpSessionConfig.forRemoteAddress("127.0.0.1", 25);
  private static final Supplier<SSLEngine> SSL_ENGINE_SUPPLIER = SmtpSessionFactoryConfig.nonProductionConfig().getSslEngineSupplier();
  private static final String EHLO_DOMAIN = "example.com";

  private MessageContent smtpContent;
  private MessageContent sevenBitContent;
  private MessageContent unknownContent;
  private MessageContent unknown7BitContent;
  private ResponseHandler responseHandler;
  private CompletableFuture<List<SmtpResponse>> responseFuture;
  private CompletableFuture<List<SmtpResponse>> secondResponseFuture;
  private Channel channel;
  private ChannelPipeline pipeline;
  private SmtpSession session;
  private ChannelFuture writeFuture;
  private ArgumentCaptor<ByteBuf> byteBufCaptor;
  private List<Object> objectsToRelease = Lists.newArrayList();
  private LoggingInterceptor log;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() {
    smtpContent = MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES));
    sevenBitContent = MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES), MessageContentEncoding.SEVEN_BIT);
    unknownContent = MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES), MessageContentEncoding.UNKNOWN);
    unknown7BitContent = MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES), MessageContentEncoding.UNKNOWN);

    channel = mock(Channel.class);
    pipeline = mock(ChannelPipeline.class);
    responseHandler = mock(ResponseHandler.class);

    byteBufCaptor = ArgumentCaptor.forClass(ByteBuf.class);
    responseFuture = new CompletableFuture<>();
    secondResponseFuture = new CompletableFuture<>();
    writeFuture = mock(ChannelFuture.class);
    when(responseHandler.createResponseFuture(anyInt(), any())).thenReturn(responseFuture, secondResponseFuture);

    when(channel.pipeline()).thenReturn(pipeline);
    when(channel.alloc()).thenReturn(new PooledByteBufAllocator(false));
    when(channel.write(any())).thenAnswer(answer -> {
      objectsToRelease.add(answer.getArgumentAt(0, Object.class));
      return writeFuture;
    });
    when(channel.writeAndFlush(any())).thenAnswer(answer -> {
      objectsToRelease.add(answer.getArgumentAt(0, Object.class));
      return writeFuture;
    });

    log = new LoggingInterceptor();
    session = new SmtpSession(channel, responseHandler, CONFIG.withSendInterceptor(log), SmtpSessionFactoryConfig.DIRECT_EXECUTOR, SSL_ENGINE_SUPPLIER);
    session.parseEhloResponse(EHLO_DOMAIN, Lists.newArrayList("PIPELINING", "AUTH PLAIN LOGIN", "CHUNKING"));
  }

  @After
  public void tearDown() {
    for (Object obj : objectsToRelease) {
      if (!(obj instanceof ReferenceCounted)) {
        continue;
      }

      ReferenceCounted referenceCountedObject = (ReferenceCounted) obj;

      assertThat(referenceCountedObject.refCnt())
          .withFailMessage("Trying to free %s but it has a ref count of %d", obj, referenceCountedObject.refCnt())
          .isEqualTo(1);

      referenceCountedObject.release();
    }
  }
  
  @Test
  public void itSendsRequests() {
    session.send(SMTP_REQUEST);

    verify(channel).writeAndFlush(SMTP_REQUEST);

    assertThat(log.getLog()).isEqualTo("NOOP");
  }

  @Test
  public void itSendsContents() {
    session.send(smtpContent);

    verify(channel).write(smtpContent.getDotStuffedContent());
    verify(channel).write(EMPTY_LAST_CONTENT);
    verify(channel).flush();

    assertThat(log.getLog()).isEqualTo("<contents>");
  }

  @Test
  public void itThrowsIllegalArgumentIfTheSubmittedMessageSizeIsLargerThanTheMaximum() {
    session.parseEhloResponse(EHLO_DOMAIN, Lists.newArrayList("SIZE 1024"));

    MessageContent largeMessage = MessageContent.of(ByteSource.wrap(new byte[1025]));

    assertThatThrownBy(() -> session.send(largeMessage))
      .isInstanceOf(MessageTooLargeException.class)
      .hasMessage("[unidentified-connection] This message is too large to be sent (max size: 1024)");
  }

  @Test
  public void itWrapsTheResponse() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().getResponses().get(0).code()).isEqualTo(OK_RESPONSE.code());
    assertThat(future.get().getResponses().get(0).details()).isEqualTo(OK_RESPONSE.details());
  }

  @Test
  public void itParsesTheEhloResponse() {
    session.send(EHLO_REQUEST);

    responseFuture.complete(Lists.newArrayList(new DefaultSmtpResponse(250,
        "smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "8BITMIME",
        "STARTTLS",
        "SIZE")));

    assertThat(session.getEhloResponse().isSupported(Extension.EIGHT_BIT_MIME)).isTrue();
    assertThat(session.getEhloResponse().isSupported(Extension.STARTTLS)).isTrue();
    assertThat(session.getEhloResponse().isSupported(Extension.SIZE)).isTrue();

    assertThat(session.getEhloResponse().isSupported(Extension.PIPELINING)).isFalse();

    assertThat(session.getEhloResponse().isAuthPlainSupported()).isTrue();
    assertThat(session.getEhloResponse().isAuthLoginSupported()).isTrue();
  }

  @Test
  public void itParsesAnEmptyEhloResponse() {
    session.send(EHLO_REQUEST);

    responseFuture.complete(Lists.newArrayList(new DefaultSmtpResponse(250,
        "smtp.example.com Hello client.example.com")));

    assertThat(session.getEhloResponse().isSupported(Extension.EIGHT_BIT_MIME)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.STARTTLS)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.SIZE)).isFalse();
    assertThat(session.getEhloResponse().isSupported(Extension.PIPELINING)).isFalse();

    assertThat(session.getEhloResponse().isAuthPlainSupported()).isFalse();
    assertThat(session.getEhloResponse().isAuthLoginSupported()).isFalse();
  }

  @Test
  public void itExecutesReturnedFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, CONFIG, executorService, SSL_ENGINE_SUPPLIER);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Void> assertionFuture = future.thenRun(() -> assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor"));

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE));
    assertionFuture.join();
  }

  @Test
  public void itExecutesReturnedExceptionalFuturesOnTheProvidedExecutor() {
    ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("SmtpSessionTestExecutor").build());
    SmtpSession session = new SmtpSession(channel, responseHandler, CONFIG, executorService, SSL_ENGINE_SUPPLIER);

    CompletableFuture<SmtpClientResponse> future = session.send(SMTP_REQUEST);
    CompletableFuture<Boolean> assertionFuture = future.handle((r, e) -> {
      assertThat(Thread.currentThread().getName()).contains("SmtpSessionTestExecutor");
      return true;
    });

    responseFuture.completeExceptionally(new RuntimeException());
    assertionFuture.join();
  }

  @Test
  public void itThrowsIllegalStateIfPipeliningIsNotSupported() {
    resetEhloExtensions();

    assertThatThrownBy(() -> session.sendPipelined(smtpContent, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Pipelining is not supported on this server");
  }

  @Test
  public void itSendsPipelinedRequests() {
    session.sendPipelined(smtpContent, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(smtpContent.getDotStuffedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).write(RCPT_REQUEST);
    order.verify(channel).write(DATA_REQUEST);
    order.verify(channel).flush();

    assertThat(log.getLog()).isEqualTo("<pipeline MAIL, RCPT, DATA>");
  }

  @Test
  public void itCanSendASingleCommandWithPipelined() {
    // this is the same as just calling send
    session.sendPipelined(MAIL_REQUEST);

    InOrder order = inOrder(channel);
    order.verify(channel).write(MAIL_REQUEST);
    order.verify(channel).flush();
  }

  @Test
  public void itChecksPipelineArgumentsAreValid() {
    assertPipelineError("DATA must appear last in a pipelined request", DATA_REQUEST, MAIL_REQUEST);
    assertPipelineError("EHLO must appear last in a pipelined request", EHLO_REQUEST, MAIL_REQUEST);
    assertPipelineError("NOOP must appear last in a pipelined request", NOOP_REQUEST, MAIL_REQUEST);

    assertPipelineError("HELO cannot be used in a pipelined request", HELO_REQUEST);
    assertPipelineError("HELP cannot be used in a pipelined request", HELP_REQUEST);
  }

  private void assertPipelineError(String message, SmtpRequest... requests) {
    assertThatThrownBy(() -> session.sendPipelined(requests))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(message);
  }

  @Test
  public void itWrapsTheResponsesWhenPipelining() throws ExecutionException, InterruptedException {
    CompletableFuture<SmtpClientResponse> future = session.sendPipelined(smtpContent, MAIL_REQUEST, RCPT_REQUEST, DATA_REQUEST);

    SmtpResponse[] responses = {OK_RESPONSE, OK_RESPONSE, OK_RESPONSE, OK_RESPONSE};
    responseFuture.complete(Lists.newArrayList(responses));

    // 4 responses expected: one for the content, 3 for the requests
    verify(responseHandler).createResponseFuture(eq(4), any());

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(responses.length);
    assertThat(future.get().getSession()).isEqualTo(session);
    assertThat(future.get().getResponses().get(0).code()).isEqualTo(OK_RESPONSE.code());

    assertThat(log.getLog()).isEqualTo("<pipeline MAIL, RCPT, DATA>, 250 OK, 250 OK, 250 OK, 250 OK");
  }

  @Test
  public void itExpectsTheRightNumberOfResponsesWhenPipelining() {
    session.sendPipelined(RCPT_REQUEST, DATA_REQUEST);

    // 1 response expected for each request
    verify(responseHandler).createResponseFuture(eq(2), any());
  }

  @Test
  public void itWillNotSendChunksUnlessTheServerSupportsIt() {
    resetEhloExtensions();

    assertThatThrownBy(() -> session.sendChunk(Unpooled.copiedBuffer(new byte[1]), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Chunking is not supported on this server");
  }

  @Test
  public void itEnforcesMessageSizeForChunkedMessages() {
    session.parseEhloResponse(EHLO_DOMAIN, Lists.newArrayList("CHUNKING", "SIZE 1024"));

    // the first message should count towards the message size
    session.sendChunk(Unpooled.copiedBuffer(new byte[1000]), false);

    assertThatThrownBy(() -> session.sendChunk(Unpooled.copiedBuffer(new byte[500]), true))
        .isInstanceOf(MessageTooLargeException.class)
        .hasMessage("[unidentified-connection] This message is too large to be sent (max size: 1024)");
  }

  @Test
  public void itResetsTheChunkedMessageSizeForTheLastChunk() {
    session.parseEhloResponse(EHLO_DOMAIN, Lists.newArrayList("CHUNKING", "SIZE 1024"));

    session.sendChunk(Unpooled.copiedBuffer(new byte[1000]), true);
    session.sendChunk(Unpooled.copiedBuffer(new byte[1000]), true);
  }

  @Test
  public void itWritesTheBdatCommandAndMessageDataWhenChunking() {
    ByteBuf buffer = Unpooled.copiedBuffer(new byte[1000]);

    session.sendChunk(buffer, false);

    verify(channel).write(new DefaultSmtpRequest(SmtpCommand.valueOf("BDAT"), Integer.toString(buffer.readableBytes())));
    verify(channel).write(buffer);
    verify(channel).flush();

    assertThat(log.getLog()).isEqualTo("<contents>");
  }

  @Test
  public void itIndicatesTheFinalChunkWhenChunking() {
    ByteBuf buffer = Unpooled.copiedBuffer(new byte[1000]);

    session.sendChunk(buffer, true);

    verify(channel).write(new DefaultSmtpRequest(SmtpCommand.valueOf("BDAT"), Integer.toString(buffer.readableBytes()), "LAST"));
    verify(channel).write(buffer);
    verify(channel).flush();
  }

  @Test
  public void itWillNotAuthenticateWithAuthPlainUnlessTheServerSupportsIt() {
    resetEhloExtensions();

    assertThatThrownBy(() -> session.authPlain("user", "password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Auth plain is not supported on this server");
  }

  @Test
  public void itCanAuthenticateWithAuthPlain() {
    String username = "user";
    String password = "password";
    String encoded = Base64.getEncoder().encodeToString(String.format("%s\0%s\0%s", username, username, password).getBytes());

    session.authPlain(username, password);

    verify(channel).writeAndFlush(new DefaultSmtpRequest("AUTH", "PLAIN", encoded));

    assertThat(log.getLog()).isEqualTo("AUTH");
  }

  @Test
  public void itWillNotAuthenticateWithAuthLoginUnlessTheServerSupportsIt() {
    resetEhloExtensions();

    assertThatThrownBy(() -> session.authLogin("user", "password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Auth login is not supported on this server");
  }

  @Test
  public void itCanAuthenticateWithAuthLogin() throws Exception {
    String username = "user";
    String password = "password";

    // do the initial request, which just includes the username
    session.authLogin("user", "password");

    verify(channel).writeAndFlush(new DefaultSmtpRequest("AUTH", "LOGIN", encodeBase64(username)));

    // now the second request, which sends the password
    responseFuture.complete(Lists.newArrayList(INTERMEDIATE_RESPONSE));

    // this is sent to the second invocation of writeAndFlush
    ArgumentCaptor<Object> bufCaptor = ArgumentCaptor.forClass(Object.class);
    verify(channel, times(2)).writeAndFlush(bufCaptor.capture());
    ByteBuf capturedBuffer = (ByteBuf) bufCaptor.getAllValues().get(1);

    String actualString = capturedBuffer.toString(0, capturedBuffer.readableBytes(), StandardCharsets.UTF_8);
    assertThat(actualString).isEqualTo(encodeBase64(password) + "\r\n");
  }

  @Test
  public void itReturnsTheResponseIfAuthLoginFailsAtTheFirstRequest() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.authLogin("user", "password");

    responseFuture.complete(Lists.newArrayList(FAIL_RESPONSE));

    assertThat(f.get().getResponses().get(0).code()).isEqualTo(FAIL_RESPONSE.code());
  }

  @Test
  public void itRedactsAuthCommandsInTheDebugString() {
    assertThat(SmtpSession.createDebugString(new DefaultSmtpRequest("AUTH", "super-secret")))
        .isEqualTo("<redacted-auth-command>");
  }

  @Test
  public void itSendsEmailsUsingChunkingIfItIsSupported() throws Exception {
    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, Lists.newArrayList(BOB, CAROL), smtpContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + CAROL + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    assertThat(getString(byteBufCaptor.getValue()))
        .isEqualTo("BDAT " + MESSAGE_CONTENTS.length() + " LAST\r\n" + MESSAGE_CONTENTS);

    assertResponsesMapped(4, future);

    assertThat(log.getLog()).isEqualTo("<pipeline MAIL, RCPT, RCPT>, 250 OK 0, 251 OK 1, 252 OK 2, 253 OK 3");
  }

  private String getString(ByteBuf byteBuf) {
    return new String(getBytes(byteBuf));
  }

  private byte[] getBytes(ByteBuf byteBuf) {
    byte[] bytes = new byte[byteBuf.readableBytes()];
    byteBuf.getBytes(0, bytes);
    return bytes;
  }

  @Test
  public void itSendsRsetBetweenSends() {
    session.send(ALICE, BOB, MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES)));
    session.send(ALICE, BOB, MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES)));

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    order.verify(channel).write(req(SmtpCommand.RSET));
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    assertThat(log.getLog()).isEqualTo("<pipeline MAIL, RCPT>, <pipeline RSET, MAIL, RCPT>");
  }

  @Test
  public void itDoesNotPipelineRsetUnlessSupportedByTheServer() {
    resetEhloExtensions();
    when(responseHandler.createResponseFuture(anyInt(), any())).thenAnswer(a -> CompletableFuture.completedFuture(Lists.newArrayList(OK_RESPONSE)));

    session.send(ALICE, BOB, MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES))).join();
    session.send(ALICE, BOB, MessageContent.of(Unpooled.copiedBuffer(MESSAGE_BYTES))).join();

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).flush();
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).flush();
    order.verify(channel).write(req(SmtpCommand.DATA));
    order.verify(channel).flush();
    order.verify(channel, times(2)).write(any(SmtpContent.class));
    order.verify(channel).flush();

    order.verify(channel).write(req(SmtpCommand.RSET));
    order.verify(channel).flush();
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).flush();
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).flush();
    order.verify(channel).write(req(SmtpCommand.DATA));
    order.verify(channel).flush();
    order.verify(channel, times(2)).write(any(SmtpContent.class));
    order.verify(channel).flush();

    assertThat(log.getLog()).isEqualTo(
        "MAIL, 250 OK, RCPT, 250 OK, DATA, 250 OK, <contents>, 250 OK, " +
        "RSET, 250 OK, " +
        "MAIL, 250 OK, RCPT, 250 OK, DATA, 250 OK, <contents>, 250 OK");
  }

  @Test
  public void itSendsEmailsUsingChunkingIfItIsSupportedWithoutPipelining() throws Exception {
    setExtensions(Extension.CHUNKING);

    when(responseHandler.createResponseFuture(anyInt(), any())).thenAnswer(a -> CompletableFuture.completedFuture(Lists.newArrayList(OK_RESPONSE)));

    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, BOB, smtpContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    assertThat(getString(byteBufCaptor.getValue()))
        .isEqualTo("BDAT " + MESSAGE_CONTENTS.length() + " LAST\r\n" + MESSAGE_CONTENTS);

    assertThat(future.isDone());
    assertThat(future.get().getResponses().size()).isEqualTo(3);

    assertThat(log.getLog()).isEqualTo("MAIL, 250 OK, RCPT, 250 OK, <contents>, 250 OK");
  }

  @Test
  public void itSendsEmailsUsingChunkingIfItIsSupportedWithoutPipeliningAndMultipleRecipients() throws Exception {
    setExtensions(Extension.CHUNKING);

    when(responseHandler.createResponseFuture(anyInt(), any())).thenAnswer(a -> CompletableFuture.completedFuture(Lists.newArrayList(OK_RESPONSE)));

    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, Lists.newArrayList(BOB, CAROL), smtpContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + CAROL + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    assertThat(getString(byteBufCaptor.getValue()))
        .isEqualTo("BDAT " + MESSAGE_CONTENTS.length() + " LAST\r\n" + MESSAGE_CONTENTS);
    
    assertThat(future.isDone());
    assertThat(future.get().getResponses().size()).isEqualTo(4);

    assertThat(log.getLog()).isEqualTo("MAIL, 250 OK, RCPT, 250 OK, RCPT, 250 OK, <contents>, 250 OK");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void itSendsChunksOneAtATime() throws Exception {
    List<String> chunks = Lists.newArrayList("first chunk", "number two", "last one");
    MessageContent content = mock(MessageContent.class);
    when(content.getContentChunkIterator(any()))
        .thenReturn(Iterators.transform(chunks.iterator(), c -> Unpooled.copiedBuffer(c.getBytes(StandardCharsets.UTF_8))));

    CompletableFuture<List<SmtpResponse>> future1 = new CompletableFuture<>();
    CompletableFuture<List<SmtpResponse>> future2 = new CompletableFuture<>();
    CompletableFuture<List<SmtpResponse>> future3 = new CompletableFuture<>();

    when(responseHandler.createResponseFuture(anyInt(), any())).thenReturn(future1, future2, future3);

    session.send(ALICE, Collections.singleton(BOB), content);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    // respond to the initial pipelined requests
    future1.complete(Lists.newArrayList(OK_RESPONSE));

    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    // respond to the second chunk
    future2.complete(Lists.newArrayList(OK_RESPONSE));

    order.verify(channel).write(byteBufCaptor.capture());
    order.verify(channel).flush();

    assertThat(getString(byteBufCaptor.getAllValues().get(0)))
        .isEqualTo("BDAT " + chunks.get(0).length() + "\r\n" + chunks.get(0));
    assertThat(getString(byteBufCaptor.getAllValues().get(1)))
        .isEqualTo("BDAT " + chunks.get(1).length() + "\r\n" + chunks.get(1));
    assertThat(getString(byteBufCaptor.getAllValues().get(2)))
        .isEqualTo("BDAT " + chunks.get(2).length() + " LAST\r\n" + chunks.get(2));

    assertThat(log.getLog()).isEqualTo("<pipeline MAIL, RCPT>, 250 OK, <contents>, 250 OK, <contents>");
  }

  @Test
  public void itSendsEmailsUsingDataIfTheContentIs7Bit() throws Exception {
    setExtensions(Extension.PIPELINING);
    assertSentAs7Bit(sevenBitContent);
  }

  @Test
  public void itSendsEmailsUsingDataIfTheContentIs7BitWithoutPipeliningAndMultipleRecipients() throws Exception {
    resetEhloExtensions();

    when(responseHandler.createResponseFuture(anyInt(), any())).thenAnswer(a -> CompletableFuture.completedFuture(Lists.newArrayList(OK_RESPONSE)));

    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, Lists.newArrayList(BOB, CAROL), sevenBitContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + CAROL + ">"));
    order.verify(channel).write(req(SmtpCommand.DATA));

    order.verify(channel).write(sevenBitContent.getDotStuffedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).flush();

    secondResponseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(5);

    assertThat(log.getLog()).isEqualTo("MAIL, 250 OK, RCPT, 250 OK, RCPT, 250 OK, DATA, 250 OK, <contents>, 250 OK");
  }

  @Test
  public void itSendsEmailsUsingDataIfTheContentIs7BitAndThereAreMultipleRecipients() throws Exception {
    setExtensions(Extension.PIPELINING);

    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, Lists.newArrayList(BOB, CAROL), sevenBitContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + CAROL + ">"));
    order.verify(channel).write(req(SmtpCommand.DATA));

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE, OK_RESPONSE, OK_RESPONSE, OK_RESPONSE));

    order.verify(channel).write(sevenBitContent.getDotStuffedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).flush();

    secondResponseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(5);
  }

  @Test
  public void itSendsEmailsUsing8BitMimeIfItIsSupported() throws Exception {
    setExtensions(Extension.EIGHT_BIT_MIME, Extension.PIPELINING);

    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, BOB, unknownContent);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">", "BODY=8BITMIME"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.DATA));

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE, OK_RESPONSE, OK_RESPONSE));

    order.verify(channel).write(smtpContent.getDotStuffedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).flush();

    secondResponseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(4);
  }

  @Test
  public void itSendsEmailsUsingDataIfTheyAreDetectedToBe7BitSafe() throws Exception {
    setExtensions(Extension.PIPELINING);
    assertSentAs7Bit(unknown7BitContent);
  }

  private void assertSentAs7Bit(MessageContent content) throws InterruptedException, ExecutionException {
    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, BOB, content);

    InOrder order = inOrder(channel);
    order.verify(channel).write(req(SmtpCommand.MAIL, "FROM:<" + ALICE + ">"));
    order.verify(channel).write(req(SmtpCommand.RCPT, "TO:<" + BOB + ">"));
    order.verify(channel).write(req(SmtpCommand.DATA));

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE, OK_RESPONSE, OK_RESPONSE));

    order.verify(channel).write(content.getDotStuffedContent());
    order.verify(channel).write(EMPTY_LAST_CONTENT);
    order.verify(channel).flush();

    secondResponseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(4);
  }

  @Test
  public void itSupportsOverridingInterceptorsForASingleSend() throws Exception {
    LoggingInterceptor localLog = new LoggingInterceptor();
    CompletableFuture<SmtpClientResponse> future = session.send(ALICE, Lists.newArrayList(BOB, CAROL), smtpContent, localLog);

    assertResponsesMapped(4, future);

    assertThat(log.getLog()).isEqualTo(""); // shared interceptors are not called
    assertThat(localLog.getLog()).isEqualTo("<pipeline MAIL, RCPT, RCPT>, 250 OK 0, 251 OK 1, 252 OK 2, 253 OK 3");
  }

  private void setExtensions(Extension... extensions) {
    session.parseEhloResponse(EHLO_DOMAIN, Arrays.stream(extensions).map(Extension::getLowerCaseName).collect(Collectors.toList()));
  }

  private void assertResponsesMapped(int responsesExpected, CompletableFuture<SmtpClientResponse> future) throws Exception {
    List<SmtpResponse> responses = Lists.newArrayList();
    for (int i = 0; i < responsesExpected; i++) {
      responses.add(new DefaultSmtpResponse(250 + i, "OK " + i));
    }

    responseFuture.complete(responses);

    verify(responseHandler).createResponseFuture(eq(responsesExpected), any());

    assertThat(future.isDone()).isTrue();
    assertThat(future.get().getResponses().size()).isEqualTo(responses.size());

    for (int i = 0; i < responsesExpected; i++) {
      assertThat(future.get().getSession()).isEqualTo(session);
      assertThat(future.get().getResponses().get(i).code()).isEqualTo(responses.get(i).code());
      assertThat(future.get().getResponses().get(i).details()).isEqualTo(responses.get(i).details());
    }
  }

  @Test
  public void itIncludesCommandsAndArgsInTheDebugString() {
    assertThat(SmtpSession.createDebugString(new DefaultSmtpRequest("EHLO", EHLO_DOMAIN), new DefaultSmtpRequest("AUTH", "super-secret")))
        .isEqualTo("EHLO example.com, <redacted-auth-command>");

  }

  private String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void itClosesTheUnderlyingChannel() {
    DefaultChannelPromise channelPromise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    when(channel.close()).thenReturn(channelPromise);

    CompletableFuture<Void> f = session.close();
    channelPromise.setSuccess();

    assertThat(f.isDone());
  }

  @Test
  public void itCompletesCloseFutureWhenTheConnectionIsClosed() throws Exception {
    assertThat(session.getCloseFuture().isDone()).isFalse();

    getErrorHandler().channelInactive(mock(ChannelHandlerContext.class));

    assertThat(session.getCloseFuture().isDone()).isTrue();
  }

  @Test
  public void itCompletesCloseFutureExceptionallyWhenTheConnectionIsClosed() throws Exception {
    ChannelInboundHandler errorHandler = getErrorHandler();

    Exception testException = new Exception();
    ChannelHandlerContext context = mock(ChannelHandlerContext.class);

    errorHandler.exceptionCaught(context, testException);

    verify(context).close();

    errorHandler.channelInactive(context);

    assertThat(session.getCloseFuture().isCompletedExceptionally()).isTrue();
    assertThatThrownBy(() -> session.getCloseFuture().get()).hasCause(testException);
  }

  @Test
  public void itFiresExceptionsWhenSendingRequests() throws Exception {
    session.send(SMTP_REQUEST);
    assertExceptionsFiredOnFailure();
  }

  @Test
  public void itFiresExceptionsWhenSendingContent() throws Exception {
    session.send(smtpContent);
    assertExceptionsFiredOnFailure();
  }

  private void assertExceptionsFiredOnFailure() throws Exception {
    // get the listener added when the channel was written to
    ArgumentCaptor<ChannelFutureListener> captor = ArgumentCaptor.forClass(ChannelFutureListener.class);
    verify(writeFuture, atLeast(1)).addListener(captor.capture());
    ChannelFutureListener addedListener = captor.getValue();

    // tell the listener the write failed
    DefaultChannelPromise promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    promise.setFailure(new Exception());
    addedListener.operationComplete(promise);

    verify(pipeline).fireExceptionCaught(promise.cause());
  }

  @Test
  public void itDeterminesEncryptionStatusByCheckingPipeline() {
    assertThat(session.isEncrypted()).isFalse();

    when(pipeline.get(SslHandler.class)).thenReturn(new SslHandler(SSL_ENGINE_SUPPLIER.get()));

    assertThat(session.isEncrypted()).isTrue();
  }

  @Test
  public void itThrowsWhenStartTlsIsCalledIfEncryptionIsActive() {
    when(pipeline.get(SslHandler.class)).thenReturn(new SslHandler(SSL_ENGINE_SUPPLIER.get()));

    assertThatThrownBy(() -> session.startTls())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("This connection is already using TLS");
  }

  @Test
  public void itReturnsTheFailureResponseWhenStartTlsIsCalledIfTheServerReturnsAnError() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();

    responseFuture.complete(Lists.newArrayList(FAIL_RESPONSE));

    assertThat(f.isDone());
    assertThat(f.get().getResponses().get(0).code()).isEqualTo(FAIL_RESPONSE.code());
  }

  @Test
  public void itAddsAnSslHandlerToThePipelineIfTheStartTlsCommandSucceeds() throws Exception {
    session.startTls();

    responseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    verify(pipeline).addFirst(any(SslHandler.class));

    assertThat(log.getLog()).isEqualTo("STARTTLS, 250 OK");
  }

  @Test
  public void itFailsTheFutureIfTheTlsHandshakeFails() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();
    responseFuture.complete(Lists.newArrayList(OK_RESPONSE));
    SslHandler sslHandler = getSslHandler();

    // fail the handshake
    Exception testException = new Exception();
    ((DefaultPromise<Channel>) sslHandler.handshakeFuture()).setFailure(testException);

    assertThat(f.isCompletedExceptionally()).isTrue();
    assertThatThrownBy(f::get).hasCause(testException);

    verify(channel).close();
  }

  @Test
  public void itReturnsTheStartTlsResponseIfTheTlsHandshakeSucceeds() throws Exception {
    CompletableFuture<SmtpClientResponse> f = session.startTls();
    responseFuture.complete(Lists.newArrayList(OK_RESPONSE));

    // respond to the ehlo sent after starttls
    secondResponseFuture.complete(Lists.newArrayList(new DefaultSmtpResponse(250,
        "smtp.example.com Hello client.example.com",
        "AUTH PLAIN LOGIN",
        "PIPELINING")));

    // the handshake succeeds
    SslHandler sslHandler = getSslHandler();
    ((DefaultPromise<Channel>) sslHandler.handshakeFuture()).setSuccess(channel);

    assertThat(f.isDone()).isTrue();
    assertThat(f.get().getResponses().get(0).code()).isEqualTo(OK_RESPONSE.code());

    // check EHLO is parsed again
    assertThat(session.getEhloResponse().isSupported(Extension.PIPELINING)).isTrue();
    assertThat(session.getEhloResponse().isSupported(Extension.STARTTLS)).isFalse();
  }

  private SslHandler getSslHandler() throws Exception {
    // get SslHandler if it was added to the pipeline
    ArgumentCaptor<ChannelHandler> captor = ArgumentCaptor.forClass(ChannelHandler.class);
    verify(pipeline).addFirst(captor.capture());
    SslHandler sslHandler = (SslHandler) captor.getValue();

    // mock and store the context so we can get the handshake future
    ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    when(context.executor()).thenReturn(ImmediateEventExecutor.INSTANCE);
    when(context.channel()).thenReturn(mock(Channel.class, Answers.RETURNS_MOCKS.get()));

    // add the handler but prevent the handshake from running automatically
    when(channel.isActive()).thenReturn(false);
    sslHandler.handlerAdded(context);

    return sslHandler;
  }

  private ChannelInboundHandler getErrorHandler() {
    ArgumentCaptor<ChannelHandler> captor = ArgumentCaptor.forClass(ChannelHandler.class);
    verify(pipeline).addLast(captor.capture());
    return (ChannelInboundHandler) captor.getValue();
  }

  private void resetEhloExtensions() {
    session.parseEhloResponse(EHLO_DOMAIN, Collections.emptyList());
  }

  private SmtpRequest req(SmtpCommand command, CharSequence... parameters) {
    return new DefaultSmtpRequest(command, parameters);
  }

  private class LoggingInterceptor implements SendInterceptor {
    private List<String> entries = Lists.newArrayList();

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundRequest(SmtpRequest request, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      entries.add(request.command().name().toString());
      return logResponses(next);
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundData(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      entries.add("<contents>");
      return logResponses(next);
    }

    @Override
    public CompletableFuture<List<SmtpResponse>> aroundPipelinedSequence(List<SmtpRequest> requests, Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      entries.add("<pipeline " + requests.stream().map(r -> r.command().name()).collect(Collectors.joining(", ")) + ">");
      return logResponses(next);
    }

    private CompletableFuture<List<SmtpResponse>> logResponses(Supplier<CompletableFuture<List<SmtpResponse>>> next) {
      return next.get().thenApply(rs -> {
        for (SmtpResponse r : rs) {
          entries.add(SmtpResponses.toString(r));
        }
        return rs;
      });
    }

    String getLog() {
      return Joiner.on(", ").join(entries);
    }
  }
}
