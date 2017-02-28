package com.hubspot.smtp;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.hubspot.smtp.client.SmtpClientResponse;
import com.hubspot.smtp.client.SmtpSession;
import com.hubspot.smtp.client.SmtpSessionConfig;
import com.hubspot.smtp.client.SmtpSessionFactory;
import com.hubspot.smtp.client.SupportedExtensions;
import com.hubspot.smtp.utils.ByteBufs;

import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.smtp.DefaultSmtpContent;
import io.netty.handler.codec.smtp.DefaultSmtpRequest;
import io.netty.handler.codec.smtp.SmtpCommand;
import io.netty.handler.codec.smtp.SmtpContent;
import io.netty.handler.codec.smtp.SmtpRequest;

class TestApp {
  private static final String TEST_EMAIL = "Subject: test mail\r\n\r\nHi there!\r\n\r\n- Michael\r\n";

  public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {

    NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    Stopwatch stopwatch = Stopwatch.createStarted();
    for (int i = 0; i < 1000; i++) {
      sendEmail(eventLoopGroup);
      return;
    }

    System.out.println("Completed in " + stopwatch.elapsed(TimeUnit.MILLISECONDS));

    while (Thread.currentThread().isAlive()) {
      Thread.sleep(500);
    }
  }

  private static void sendEmail(NioEventLoopGroup eventLoopGroup) throws InterruptedException, ExecutionException {
    SmtpClient client = new SmtpClient(eventLoopGroup, new SmtpSessionFactory());

    // todo: ensure dot stuffed messages end with CR
    // todo: allocate dot stuffed messages with the right allocator
    ByteBuf messageBuffer = ByteBufs.createDotStuffedBuffer(TEST_EMAIL.getBytes(StandardCharsets.UTF_8));

    client.connect(SmtpSessionConfig.forRemoteAddress(InetSocketAddress.createUnresolved("localhost", 9925)))
        .thenCompose(r -> client.ehlo(r.getSession(), "hubspot.com"))
        .thenCompose(r -> client.mail(r.getSession(), "mobrien@hubspot.com"))
        .thenCompose(r -> client.rcpt(r.getSession(), "michael@mcobrien.org"))
        .thenCompose(r -> client.data(r.getSession(), messageBuffer))
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
      return sessionFactory.connect(group, config);
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
      return send(session, request(SmtpCommand.EHLO, greetingName)).whenComplete((response, cause) -> {
        if (response != null) {
          setSupportedExtensions(session, response.details());
        }
      });
    }

    private void setSupportedExtensions(SmtpSession session, List<CharSequence> details) {
      EnumSet<SupportedExtensions> discoveredExtensions = EnumSet.noneOf(SupportedExtensions.class);

      for (CharSequence ext : details) {
        List<String> parts = Splitter.on(CharMatcher.WHITESPACE).splitToList(ext);
        SupportedExtensions.find(parts.get(0)).ifPresent(discoveredExtensions::add);
      }

      session.setSupportedExtensions(discoveredExtensions);
    }

    CompletableFuture<SmtpClientResponse> mail(SmtpSession session, String from) {
      return send(session, request(SmtpCommand.MAIL, "FROM:" + from));
    }

    CompletableFuture<SmtpClientResponse> rcpt(SmtpSession session, String to) {
      return send(session, request(SmtpCommand.RCPT, "TO:" + to));
    }

    CompletableFuture<SmtpClientResponse> data(SmtpSession session, ByteBuf messageBuffer) {
      return send(session, request(SmtpCommand.DATA))
          .thenCompose(r -> send(session, new DefaultSmtpContent(messageBuffer), EMPTY_LAST_CONTENT));
    }

    CompletableFuture<SmtpClientResponse> quit(SmtpSession session) {
      return send(session, request(SmtpCommand.QUIT));
    }

    private SmtpRequest request(SmtpCommand command, CharSequence... arguments) {
      return new DefaultSmtpRequest(command, arguments);
    }

    CompletableFuture<SmtpClientResponse> send(SmtpSession session, SmtpRequest request) {
      return session.send(request);
    }

    private CompletableFuture<SmtpClientResponse> send(SmtpSession session, SmtpContent... contents) {
      return session.send(contents);
    }
  }
}
