# NioSmtpClient


*A high-performance async HubSpot SMTP client.*

[![Build Status](https://travis-ci.org/HubSpot/NioSmtpClient.svg?branch=master)](https://travis-ci.org/HubSpot/NioSmtpClient)


## Pre-ALPHA

Note that this project is currently under development.
Please do not try to use this in its current form.

## Dependencies

This project depends on Java8 and Netty 4.1.8

## License

Apache 2.0

## Java Docs

See http://github.hubspot.com/NioSmtpClient/0.0.1-SNAPSHOT/


## Notes For Developers

- NEVER execute blocking commands on an eventloop thread (i.e. `CountDownLatch.await` or `Future.get`)
- Avoid doing long running tasks on event loop threads
- Use `new` as sparingly as possible:
  - Share objects when possible
  - Use Netty ByteBuf allocators when possible
