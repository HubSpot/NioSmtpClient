package com.hubspot.smtp;

import static io.netty.handler.codec.smtp.SmtpCommand.DATA;
import static io.netty.handler.codec.smtp.SmtpCommand.EHLO;
import static io.netty.handler.codec.smtp.SmtpCommand.MAIL;
import static io.netty.handler.codec.smtp.SmtpCommand.QUIT;
import static io.netty.handler.codec.smtp.SmtpCommand.RCPT;
import static io.netty.handler.codec.smtp.SmtpCommand.RSET;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.base.Stopwatch;
import com.hubspot.smtp.client.SmtpClientResponse;
import com.hubspot.smtp.client.SmtpSession;
import com.hubspot.smtp.client.SmtpSessionConfig;
import com.hubspot.smtp.client.SmtpSessionFactory;
import com.hubspot.smtp.client.SmtpSessionFactoryConfig;
import com.hubspot.smtp.messages.MessageContent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpRequest;

@SuppressWarnings("ALL")
class TestApp {
  private static final String TEST_EMAIL = "Subject: test mail\r\n\r\nHi there!\r\n\r\n- Michael\r\n";
  private static final SmtpSessionFactoryConfig FACTORY_CONFIG = SmtpSessionFactoryConfig.nonProductionConfig();

  public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {
    Stopwatch stopwatch = Stopwatch.createStarted();

    sendPipelinedEmails(100_000);
//    connectAndWait();

    System.out.println("Completed in " + stopwatch.elapsed(TimeUnit.MILLISECONDS));

    FACTORY_CONFIG.getEventLoopGroup().shutdownGracefully().sync();
  }

  private static void connectAndWait() throws InterruptedException {
    SmtpSessionConfig config = SmtpSessionConfig.forRemoteAddress("localhost", 9925)
        .withReadTimeout(Duration.ofSeconds(10)).withKeepAliveTimeout(Duration.ofSeconds(3));

    SmtpSessionFactory sessionFactory = new SmtpSessionFactory(FACTORY_CONFIG);
    sessionFactory.connect(config).thenCompose(r -> r.getSession().send(req(EHLO, "hubspot.com")));

    while (true) {
      Thread.sleep(500);
    }
  }

  private static void sendPipelinedEmails(int messageCount)  {
    ByteBuf messageBuffer = Unpooled.wrappedBuffer(TEST_EMAIL.getBytes(StandardCharsets.UTF_8));
    Supplier<MessageContent> contentProvider = () -> MessageContent.of(messageBuffer);

    SmtpSessionConfig config = SmtpSessionConfig.forRemoteAddress("localhost", 9925);

    CompletableFuture<SmtpClientResponse> future = new SmtpSessionFactory(FACTORY_CONFIG).connect(config)
        .thenCompose(r -> r.getSession().send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> r.getSession().sendPipelined(req(MAIL, "FROM:test@example.com"), req(RCPT, "TO:person1@example.com"), req(DATA)));

    for (int i = 1; i < messageCount; i++) {
      String recipient = "TO:person" + i + "@example.com";
      future = future.thenCompose(r -> r.getSession().sendPipelined(contentProvider.get(), req(RSET), req(MAIL, "FROM:test@example.com"), req(RCPT, recipient), req(DATA)));
    }

    future.thenCompose(r -> r.getSession().sendPipelined(contentProvider.get(), req(QUIT)))
        .thenCompose(r -> r.getSession().close())
        .join();
  }

  private static SmtpRequest req(SmtpCommand command, CharSequence... arguments) {
    return new DefaultSmtpRequest(command, arguments);
  }

  private static void sendEmail(NioEventLoopGroup eventLoopGroup) throws InterruptedException, ExecutionException {
    SmtpClient client = new SmtpClient(eventLoopGroup, new SmtpSessionFactory(FACTORY_CONFIG));

    client.connect(SmtpSessionConfig.forRemoteAddress("localhost", 9925))
        .thenCompose(r -> client.ehlo(r.getSession(), "hubspot.com"))
        .thenCompose(r -> client.mail(r.getSession(), "mobrien@hubspot.com"))
        .thenCompose(r -> client.rcpt(r.getSession(), "michael@mcobrien.org"))
        .thenCompose(r -> client.data(r.getSession(), MessageContent.of(Unpooled.wrappedBuffer(TEST_EMAIL.getBytes(StandardCharsets.UTF_8)))))
        .thenCompose(r -> client.quit(r.getSession()))
        .thenCompose(r -> r.getSession().close())
        .get();
  }

  // this client is just to make testing easier; it's not intended to be exposed from the library
  static class SmtpClient {
    private final NioEventLoopGroup group;
    private final SmtpSessionFactory sessionFactory;

    SmtpClient(NioEventLoopGroup group, SmtpSessionFactory sessionFactory) {
      this.group = group;
      this.sessionFactory = sessionFactory;
    }

    CompletableFuture<SmtpClientResponse> connect(SmtpSessionConfig config) {
      return sessionFactory.connect(config);
    }

    CompletableFuture<SmtpClientResponse> noop(SmtpSession session) {
      return send(session, request(SmtpCommand.NOOP));
    }

    CompletableFuture<SmtpClientResponse> rset(SmtpSession session) {
      return send(session, request(SmtpCommand.RSET));
    }

    CompletableFuture<SmtpClientResponse> helo(SmtpSession session, String greetingName) {
      return send(session, request(SmtpCommand.HELO, greetingName));
    }

    CompletableFuture<SmtpClientResponse> ehlo(SmtpSession session, String greetingName) {
      return send(session, request(EHLO, greetingName));
    }

    CompletableFuture<SmtpClientResponse> mail(SmtpSession session, String from) {
      return send(session, request(SmtpCommand.MAIL, "FROM:" + from));
    }

    CompletableFuture<SmtpClientResponse> rcpt(SmtpSession session, String to) {
      return send(session, request(RCPT, "TO:" + to));
    }

    CompletableFuture<SmtpClientResponse> data(SmtpSession session, MessageContent content) {
      return send(session, request(SmtpCommand.DATA))
          .thenCompose(r -> send(session, content));
    }

    CompletableFuture<SmtpClientResponse> quit(SmtpSession session) {
      return send(session, request(SmtpCommand.QUIT));
    }

    SmtpRequest request(SmtpCommand command, CharSequence... arguments) {
      return new DefaultSmtpRequest(command, arguments);
    }

    CompletableFuture<SmtpClientResponse> send(SmtpSession session, SmtpRequest request) {
      return session.send(request);
    }

    CompletableFuture<SmtpClientResponse> send(SmtpSession session, MessageContent contents) {
      return session.send(contents);
    }
  }
}
