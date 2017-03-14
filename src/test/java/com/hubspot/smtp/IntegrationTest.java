package com.hubspot.smtp;

import static io.netty.handler.codec.smtp.SmtpCommand.DATA;
import static io.netty.handler.codec.smtp.SmtpCommand.EHLO;
import static io.netty.handler.codec.smtp.SmtpCommand.MAIL;
import static io.netty.handler.codec.smtp.SmtpCommand.QUIT;
import static io.netty.handler.codec.smtp.SmtpCommand.RCPT;
import static io.netty.handler.codec.smtp.SmtpCommand.RSET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLEngine;

import org.apache.james.protocols.api.Encryption;
import org.apache.james.protocols.api.logger.Logger;
import org.apache.james.protocols.netty.NettyServer;
import org.apache.james.protocols.smtp.MailEnvelope;
import org.apache.james.protocols.smtp.SMTPConfigurationImpl;
import org.apache.james.protocols.smtp.SMTPProtocol;
import org.apache.james.protocols.smtp.SMTPProtocolHandlerChain;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.hook.AuthHook;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.MessageHook;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.io.ByteSource;
import com.google.common.io.CharStreams;
import com.hubspot.smtp.client.SmtpClientResponse;
import com.hubspot.smtp.client.SmtpSession;
import com.hubspot.smtp.client.SmtpSessionConfig;
import com.hubspot.smtp.client.SmtpSessionFactory;
import com.hubspot.smtp.client.Extension;
import com.hubspot.smtp.messages.MessageContent;
import com.hubspot.smtp.messages.MessageContentEncoding;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class IntegrationTest {
  private static final String CORRECT_USERNAME = "smtp-user";
  private static final String CORRECT_PASSWORD = "correct horse battery staple";
  private static final String RETURN_PATH = "return-path@example.com";
  private static final String RECIPIENT = "sender@example.com";
  private static final String MESSAGE_DATA = "From: <from@example.com>\r\n" +
      "To: <recipient@example.com>\r\n" +
      "Subject: test mail\r\n\r\n" +
      "Hello!\r\n";

  private static final NioEventLoopGroup EVENT_LOOP_GROUP = new NioEventLoopGroup();
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

  private InetSocketAddress serverAddress;
  private NettyServer smtpServer;
  private SmtpSessionFactory sessionFactory;
  private List<MailEnvelope> receivedMails;
  private Logger serverLog;
  private boolean requireAuth;

  @Before
  public void setup() throws Exception {
    receivedMails = Lists.newArrayList();
    serverAddress = new InetSocketAddress(getFreePort());
    serverLog = mock(Logger.class);
    smtpServer = createAndStartSmtpServer(serverLog, serverAddress);
    sessionFactory = new SmtpSessionFactory(EVENT_LOOP_GROUP, EXECUTOR_SERVICE);

    when(serverLog.isDebugEnabled()).thenReturn(true);
  }

  private NettyServer createAndStartSmtpServer(Logger log, InetSocketAddress address) throws Exception {
    SMTPConfigurationImpl config = new SMTPConfigurationImpl() {
      @Override
      public boolean isAuthRequired(String remoteIP) {
        return requireAuth;
      }
    };
    SMTPProtocolHandlerChain chain = new SMTPProtocolHandlerChain(new CollectEmailsHook());

    NettyServer server = new NettyServer(new SMTPProtocol(chain, config, log), Encryption.createStartTls(FakeTlsContext.createContext()));
    server.setListenAddresses(address);
    server.bind();

    return server;
  }

  @After
  public void after() {
    smtpServer.unbind();
  }

  @Test
  public void itCanParseTheEhloResponse() throws Exception {
    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> {
          assertThat(r.getSession().isSupported(Extension.PIPELINING)).isTrue();
          assertThat(r.getSession().isSupported(Extension.EIGHT_BIT_MIME)).isTrue();
          return r.getSession().send(req(QUIT));
        })
        .thenCompose(r -> assertSuccess(r).close())
        .get();
  }

  @Test
  public void itCanAuthenticateWithAuthPlain() throws Exception {
    requireAuth = true;

    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> {
          assertThat(r.getSession().isSupported(Extension.AUTH)).isTrue();
          assertThat(r.getSession().isAuthPlainSupported()).isTrue();
          return r.getSession().authPlain(CORRECT_USERNAME, CORRECT_PASSWORD);
        })
        .thenCompose(r -> assertSuccess(r).close())
        .get();
  }

  @Test
  public void itCanAuthenticateWithAuthLogin() throws Exception {
    requireAuth = true;

    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> {
          assertThat(r.getSession().isSupported(Extension.AUTH)).isTrue();
          assertThat(r.getSession().isAuthLoginSupported()).isTrue();
          return r.getSession().authLogin(CORRECT_USERNAME, CORRECT_PASSWORD);
        })
        .thenCompose(r -> assertSuccess(r).close())
        .get();
  }

  @Test
  public void itCanSendAnEmail() throws Exception {
    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> assertSuccess(r).send(req(MAIL, "FROM:<" + RETURN_PATH + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(RCPT, "TO:<" + RECIPIENT + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(DATA)))
        .thenCompose(r -> assertSuccess(r).send(createMessageContent()))
        .thenCompose(r -> assertSuccess(r).send(req(QUIT)))
        .thenCompose(r -> assertSuccess(r).close())
        .get();

    assertThat(receivedMails.size()).isEqualTo(1);
    MailEnvelope mail = receivedMails.get(0);

    assertThat(mail.getSender().toString()).isEqualTo(RETURN_PATH);
    assertThat(mail.getRecipients().get(0).toString()).isEqualTo(RECIPIENT);
    assertThat(readContents(mail)).contains(MESSAGE_DATA);
  }

  @Test
  public void itCanSendAnEmailUsingAStream() throws Exception {
    String messageText = repeat(repeat("0123456789", 7) + "\r\n", 10_000);
    MessageContent messageContent = MessageContent.of(ByteSource.wrap(messageText.getBytes()), messageText.length(), MessageContentEncoding.ASSUME_DOT_STUFFED);

    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> assertSuccess(r).send(req(MAIL, "FROM:<" + RETURN_PATH + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(RCPT, "TO:<" + RECIPIENT + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(DATA)))
        .thenCompose(r -> assertSuccess(r).send(messageContent))
        .thenCompose(r -> assertSuccess(r).send(req(QUIT)))
        .thenCompose(r -> assertSuccess(r).close())
        .get();

    assertThat(receivedMails.size()).isEqualTo(1);
    MailEnvelope mail = receivedMails.get(0);

    assertThat(mail.getSender().toString()).isEqualTo(RETURN_PATH);
    assertThat(mail.getRecipients().get(0).toString()).isEqualTo(RECIPIENT);
    assertThat(readContents(mail)).contains(messageText);
  }

  private String repeat(String s, int n) {
    return new String(new char[n]).replace("\0", s);
  }

  @Test
  public void itCanUseStartTlsToSendAnEmail() throws Exception {
    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> assertSuccess(r).startTls())
        .thenCompose(r -> assertSuccess(r).send(req(MAIL, "FROM:<" + RETURN_PATH + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(RCPT, "TO:<" + RECIPIENT + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(DATA)))
        .thenCompose(r -> assertSuccess(r).send(createMessageContent()))
        .thenCompose(r -> assertSuccess(r).send(req(QUIT)))
        .thenCompose(r -> assertSuccess(r).close())
        .get();

    assertThat(receivedMails.size()).isEqualTo(1);
  }

  @Test
  public void itClosesTheConnectionIfTheTlsHandshakeFails() throws Exception {
    // not using the insecure trust manager here so the connection will fail
    CompletableFuture<SmtpClientResponse> f = connect(SmtpSessionConfig.forRemoteAddress(serverAddress))
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> assertSuccess(r).startTls());

    assertThat(f.isCompletedExceptionally());
  }

  @Test
  public void itCanSendWithPipelining() throws Exception {
    // connect and send the initial message metadata
    CompletableFuture<SmtpClientResponse[]> future = connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "example.com")))
        .thenCompose(r -> assertSuccess(r).sendPipelined(req(MAIL, "FROM:<return-path@example.com>"), req(RCPT, "TO:<person1@example.com>"), req(DATA)));

    // send the data for the current message and the metadata for the next one, nine times
    for (int i = 1; i < 10; i++) {
      String recipient = "TO:<person" + i + "@example.com>";
      future = future.thenCompose(r -> assertSuccess(r[0]).sendPipelined(createMessageContent(), req(RSET), req(MAIL, "FROM:<return-path@example.com>"), req(RCPT, recipient), req(DATA)));
    }

    // finally send the data for the tenth message and quit
    future.thenCompose(r -> assertSuccess(r[0]).sendPipelined(createMessageContent(), req(QUIT)))
        .thenCompose(r -> assertSuccess(r[0]).close())
        .join();

    assertThat(receivedMails.size()).isEqualTo(10);
  }

  @Test
  public void itCanSendMultipleEmailsAtOnce() throws Exception {
    List<CompletableFuture<Void>> futures = Lists.newArrayList();

    for (int i = 0; i < 100; i++) {
      futures.add(connect()
          .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
          .thenCompose(r -> assertSuccess(r).send(req(MAIL, "FROM:<" + RETURN_PATH + ">")))
          .thenCompose(r -> assertSuccess(r).send(req(RCPT, "TO:<" + RECIPIENT + ">")))
          .thenCompose(r -> assertSuccess(r).send(req(DATA)))
          .thenCompose(r -> assertSuccess(r).send(createMessageContent()))
          .thenCompose(r -> assertSuccess(r).send(req(QUIT)))
          .thenCompose(r -> assertSuccess(r).close()));
    }

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();

    assertThat(receivedMails.size()).isEqualTo(100);
  }

  @Test
  public void itSendsKeepAliveCommands() throws Exception {
    connect(SmtpSessionConfig.forRemoteAddress(serverAddress).withKeepAliveTimeout(Duration.ofSeconds(1)))
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")));

    Thread.sleep(3000);

    verify(serverLog, atLeast(2)).debug(endsWith("received: NOOP"));
  }

  private String readContents(MailEnvelope mail) throws IOException {
    return CharStreams.toString(new InputStreamReader(mail.getMessageInputStream()));
  }

  private SmtpSession assertSuccess(SmtpClientResponse r) {
    assertThat(r.code() < 400).withFailMessage("Received error: " + r).isTrue();
    return r.getSession();
  }

  private CompletableFuture<SmtpClientResponse> connect() {
    return connect(SmtpSessionConfig.forRemoteAddress(serverAddress).withSSLEngineSupplier(this::createInsecureSSLEngine));
  }

  private SSLEngine createInsecureSSLEngine() {
    try {
      return SslContextBuilder
          .forClient()
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .build()
          .newEngine(PooledByteBufAllocator.DEFAULT);
    } catch (Exception e) {
      throw new RuntimeException("Could not create SSLEngine", e);
    }
  }

  private CompletableFuture<SmtpClientResponse> connect(SmtpSessionConfig config) {
    return sessionFactory.connect(config);
  }

  private static SmtpRequest req(SmtpCommand command, CharSequence... arguments) {
    return new DefaultSmtpRequest(command, arguments);
  }

  private synchronized static int getFreePort() {
    for (int port = 20000; port <= 30000; port++) {
      try {
        ServerSocket socket = new ServerSocket(port);
        socket.setReuseAddress(true);
        socket.close();
        return port;
      } catch (IOException ignored) {
        // ignore
      }
    }

    throw new RuntimeException("Could not find a port to listen on");
  }

  private MessageContent createMessageContent() {
    return MessageContent.of(Unpooled.wrappedBuffer(MESSAGE_DATA.getBytes(StandardCharsets.UTF_8)));
  }

  private class CollectEmailsHook implements MessageHook, AuthHook {
    @Override
    public synchronized HookResult onMessage(SMTPSession session, MailEnvelope mail) {
      receivedMails.add(mail);
      return HookResult.ok();
    }

    @Override
    public HookResult doAuth(SMTPSession session, String username, String password) {
      if (username.equals(CORRECT_USERNAME) && password.equals(CORRECT_PASSWORD)) {
        return HookResult.ok();
      } else {
        return HookResult.deny();
      }
    }
  }
}
