package com.hubspot.smtp;

import static io.netty.handler.codec.smtp.LastSmtpContent.EMPTY_LAST_CONTENT;
import static io.netty.handler.codec.smtp.SmtpCommand.DATA;
import static io.netty.handler.codec.smtp.SmtpCommand.EHLO;
import static io.netty.handler.codec.smtp.SmtpCommand.MAIL;
import static io.netty.handler.codec.smtp.SmtpCommand.QUIT;
import static io.netty.handler.codec.smtp.SmtpCommand.RCPT;
import static io.netty.handler.codec.smtp.SmtpCommand.RSET;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
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

@SuppressWarnings("ALL")
class TestApp {
  private static final String TEST_EMAIL = "Subject: test mail\r\n\r\nHi there!\r\n\r\n- Michael\r\n";
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

  public static void main(String[] args) throws InterruptedException, IOException, ExecutionException {
    NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    Stopwatch stopwatch = Stopwatch.createStarted();

    sendPipelinedEmails(eventLoopGroup, 100_000);

    System.out.println("Completed in " + stopwatch.elapsed(TimeUnit.MILLISECONDS));

    eventLoopGroup.shutdownGracefully().sync();
  }

  private static void sendPipelinedEmails(NioEventLoopGroup eventLoopGroup, int messageCount)  {
    ByteBuf messageBuffer = ByteBufs.createDotStuffedBuffer(TEST_EMAIL.getBytes(StandardCharsets.UTF_8));
    List<SmtpContent> contents = Lists.newArrayList(new DefaultSmtpContent(messageBuffer), EMPTY_LAST_CONTENT);

    SmtpSessionConfig config = SmtpSessionConfig.forRemoteAddress(InetSocketAddress.createUnresolved("localhost", 9925));

    CompletableFuture<SmtpClientResponse[]> future = new SmtpSessionFactory(EXECUTOR_SERVICE).connect(eventLoopGroup, config)
        .thenCompose(r -> r.getSession().send(req(EHLO, "hubspot.com")))
        .thenCompose(r -> r.getSession().sendPipelined(req(MAIL, "FROM:test@example.com"), req(RCPT, "TO:person1@example.com"), req(DATA)));

    for (int i = 1; i < messageCount; i++) {
      messageBuffer.retain();

      String recipient = "TO:person" + i + "@example.com";
      future = future.thenCompose(r -> r[0].getSession().sendPipelined(contents, req(RSET), req(MAIL, "FROM:test@example.com"), req(RCPT, recipient), req(DATA)));
    }

    future.thenCompose(r -> r[0].getSession().sendPipelined(contents,  req(QUIT)))
        .thenCompose(r -> r[0].getSession().close())
        .join();
  }

  private static SmtpRequest req(SmtpCommand command, CharSequence... arguments) {
    return new DefaultSmtpRequest(command, arguments);
  }

  private static void sendEmail(NioEventLoopGroup eventLoopGroup) throws InterruptedException, ExecutionException {
    SmtpClient client = new SmtpClient(eventLoopGroup, new SmtpSessionFactory(EXECUTOR_SERVICE));

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
      return send(session, request(EHLO, greetingName)).whenComplete((response, cause) -> {
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
      return send(session, request(RCPT, "TO:" + to));
    }

    CompletableFuture<SmtpClientResponse> data(SmtpSession session, ByteBuf messageBuffer) {
      return send(session, request(SmtpCommand.DATA))
          .thenCompose(r -> send(session, new DefaultSmtpContent(messageBuffer), EMPTY_LAST_CONTENT));
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

    CompletableFuture<SmtpClientResponse> send(SmtpSession session, SmtpContent... contents) {
      return session.send(contents);
    }
  }
}
