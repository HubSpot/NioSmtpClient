/**
 * This package contains classes to create and maintain connections to SMTP servers.
 *
 * <p>To connect to a remote server:
 *
 * <ol><li>Create a {@link com.hubspot.smtp.client.SmtpSessionFactoryConfig} to define application-wide settings
 *
 * <li>Pass this to the {@link com.hubspot.smtp.client.SmtpSessionFactory} constructor to create a factory
 *
 * <li>Create a {@link com.hubspot.smtp.client.SmtpSessionConfig} to specify the address of a remote server
 *
 * <li>Call {@link com.hubspot.smtp.client.SmtpSessionFactory#connect(com.hubspot.smtp.client.SmtpSessionConfig)} with
 * this configuration
 * </ol>
 *
 * <p>Once you have an active session, you can send emails:
 *
 * <ol><li>Initialise the connection by calling {@link com.hubspot.smtp.client.SmtpSession#send(io.netty.handler.codec.smtp.SmtpRequest)} with an EHLO command
 *
 * <li>Optionally enable TLS encryption by calling {@link com.hubspot.smtp.client.SmtpSession#startTls()} if TLS is supported
 *
 * <li>Send an email by calling {@link com.hubspot.smtp.client.SmtpSession#send(java.lang.String, java.lang.String, com.hubspot.smtp.messages.MessageContent)} or
 * one of its overloads.
 * </ol>
 *
 */
package com.hubspot.smtp.client;
