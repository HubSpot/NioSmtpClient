NioSmtpClient
==============

A high-performance async HubSpot SMTP client.

### Notes For Developers

- NEVER execute blocking commands on an eventloop thread (i.e. `CountDownLatch.await` or `Future.get`)
- Avoid doing long running tasks on event loop threads
- Use `new` as sparingly as possible:
  - Share objects when possible
  - Use Netty ByteBuf allocators when possible
