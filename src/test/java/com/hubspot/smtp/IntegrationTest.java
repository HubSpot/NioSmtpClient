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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.apache.james.protocols.api.logger.Logger;
import org.apache.james.protocols.netty.NettyServer;
import org.apache.james.protocols.smtp.MailEnvelope;
import org.apache.james.protocols.smtp.SMTPConfigurationImpl;
import org.apache.james.protocols.smtp.SMTPProtocol;
import org.apache.james.protocols.smtp.SMTPProtocolHandlerChain;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.MessageHook;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.hubspot.smtp.client.SmtpClientResponse;
import com.hubspot.smtp.client.SmtpSession;
import com.hubspot.smtp.client.SmtpSessionConfig;
import com.hubspot.smtp.client.SmtpSessionFactory;
import com.hubspot.smtp.messages.MessageContent;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;

public class IntegrationTest {
  private static final String RETURN_PATH = "return-path@example.com";
  private static final String RECIPIENT = "sender@example.com";
  private static final String MESSAGE_DATA = "From: <from@example.com>\r\n" +
      "To: <recipient@example.com>\r\n" +
      "Subject: test mail\r\n\r\n" +
      "Hello!\r\n";

  private static final MessageContent MESSAGE_CONTENT = MessageContent.of(Unpooled.wrappedBuffer(MESSAGE_DATA.getBytes()));

  private static final NioEventLoopGroup EVENT_LOOP_GROUP = new NioEventLoopGroup();
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

  private InetSocketAddress serverAddress;
  private NettyServer smtpServer;
  private SmtpSessionFactory sessionFactory;
  private List<MailEnvelope> receivedMails;
  private Logger logger;

  @Before
  public void setup() throws Exception {
    receivedMails = Lists.newArrayList();
    serverAddress = new InetSocketAddress(getFreePort());

    SMTPConfigurationImpl config = new SMTPConfigurationImpl();
    SMTPProtocolHandlerChain chain = new SMTPProtocolHandlerChain(new CollectEmailsHook());

    logger = mock(Logger.class);
    smtpServer = new NettyServer(new SMTPProtocol(chain, config, logger));
    smtpServer.setListenAddresses(serverAddress);
    smtpServer.bind();

    sessionFactory = new SmtpSessionFactory(EVENT_LOOP_GROUP, EXECUTOR_SERVICE);

    when(logger.isDebugEnabled()).thenReturn(true);
  }

  @After
  public void after() {
    smtpServer.unbind();
  }

  @Test
  public void itCanSendAnEmail() throws Exception {
    connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> assertSuccess(r).send(req(MAIL, "FROM:<" + RETURN_PATH + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(RCPT, "TO:<" + RECIPIENT + ">")))
        .thenCompose(r -> assertSuccess(r).send(req(DATA)))
        .thenCompose(r -> assertSuccess(r).send(MESSAGE_CONTENT))
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
  public void itCanSendWithPipelining() throws Exception {
    Supplier<MessageContent> contentProvider = () -> MessageContent.of(Unpooled.wrappedBuffer(MESSAGE_DATA.getBytes(StandardCharsets.UTF_8)));

    // connect and send the initial message metadata
    CompletableFuture<SmtpClientResponse[]> future = connect()
        .thenCompose(r -> assertSuccess(r).send(req(EHLO, "example.com")))
        .thenCompose(r -> assertSuccess(r).sendPipelined(req(MAIL, "FROM:<return-path@example.com>"), req(RCPT, "TO:<person1@example.com>"), req(DATA)));

    // send the data for the current message and the metadata for the next one, nine times
    for (int i = 1; i < 10; i++) {
      String recipient = "TO:<person" + i + "@example.com>";
      future = future.thenCompose(r -> assertSuccess(r[0]).sendPipelined(contentProvider.get(), req(RSET), req(MAIL, "FROM:<return-path@example.com>"), req(RCPT, recipient), req(DATA)));
    }

    // finally send the data for the tenth message and quit
    future.thenCompose(r -> assertSuccess(r[0]).sendPipelined(contentProvider.get(), req(QUIT)))
        .thenCompose(r -> assertSuccess(r[0]).close())
        .join();

    assertThat(receivedMails.size()).isEqualTo(10);
  }

  private String readContents(MailEnvelope mail) throws IOException {
    return CharStreams.toString(new InputStreamReader(mail.getMessageInputStream()));
  }

  private SmtpSession assertSuccess(SmtpClientResponse r) {
    assertThat(r.code() < 400).withFailMessage("Received error: " + r).isTrue();
    return r.getSession();
  }

  private CompletableFuture<SmtpClientResponse> connect() {
    return sessionFactory.connect(SmtpSessionConfig.forRemoteAddress(serverAddress));
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

  private class CollectEmailsHook implements MessageHook {
    @Override
    public HookResult onMessage(SMTPSession session, MailEnvelope mail) {
      receivedMails.add(mail);
      return HookResult.ok();
    }
  }
}
