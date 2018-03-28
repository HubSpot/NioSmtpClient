NioSmtpClient [![Build Status](https://travis-ci.org/HubSpot/NioSmtpClient.svg?branch=master)](https://travis-ci.org/HubSpot/NioSmtpClient)
=============

High performance SMTP client in Java based on [Netty](https://netty.io/). This client is well tested and heavily used at HubSpot.

### Features

- High performance, designed to handle many concurrent connections
- Supports TLS, pipelining, chunking, `SIZE`, `SMTPUTF8`, and other ESMTP RFCs
- Send from specific IP addresses
- Optionally keeps connections alive to send subsequent emails
- Parses the `EHLO` response and chooses the most-efficient sending method
- Interceptor support to modify or instrument commands
- Comprehensive unit and integration tests

## Dependencies

This project depends on Java8, Netty, and Guava.

## Java Docs

See http://github.hubspot.com/NioSmtpClient/1.0.0/

### Notes For Developers

- NEVER execute blocking commands on an eventloop thread (i.e. `CountDownLatch.await` or `Future.get`)
  - Calls out to unknown functions (i.e. callbacks or event listeners) should always be done on isolated thread pools.
  - Slow but not necessarily blocking operations should also be avoided
- Attempt to avoid doing long running tasks on event loop threads
- Use `new` as sparingly as possible to avoid creating garbage:
  - Share objects when possible
  - Use netty bytebuf allocators when possible
  - Use netty recyclers for objects that can be recycled.

## License

Apache 2.0
