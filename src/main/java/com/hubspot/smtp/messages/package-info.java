/**
 * This package contains classes that represent the content of an email.
 *
 * <p>You can create {@link com.hubspot.smtp.messages.MessageContent} instances by calling
 * {@link com.hubspot.smtp.messages.MessageContent#of(com.google.common.io.ByteSource)} or one of its
 * overloads. {@code MessageContent} instances can be passed to {@link com.hubspot.smtp.client.SmtpSession#send(java.lang.String, java.lang.String, com.hubspot.smtp.messages.MessageContent)}
 * to send an email.
 *
 */
package com.hubspot.smtp.messages;
