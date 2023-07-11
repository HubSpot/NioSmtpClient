package com.hubspot.smtp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class FakeProxyServer {

  private final ServerSocket proxySocket;
  private final  String proxyHost;
  private final int remotePort;
  private final byte[] request = new byte[1024];
  private final byte[] reply = new byte[4096];
  private volatile boolean isRunning = true;

  public FakeProxyServer(int port, String proxyHost, int remotePort) throws IOException {
    this.proxySocket = new ServerSocket(port);
    this.proxyHost = proxyHost;
    this.remotePort = remotePort;
    new Thread(() -> run()).start();
  }

  public void close() {
    this.isRunning = false;
  }
  private void run() {
    while (isRunning) {
      Socket clientSocket = null, serverSocket = null;
      try {
        clientSocket = proxySocket.accept();

        final InputStream inputStreamClient = clientSocket.getInputStream();
        final OutputStream outputStreamClient = clientSocket.getOutputStream();

        try {
          serverSocket = new Socket(proxyHost, remotePort);
        } catch (IOException e) {
          PrintWriter out = new PrintWriter(outputStreamClient);
          out.print("The Proxy Server could not connect to " + proxyHost + ":"
              + remotePort + ":\n" + e + "\n");
          out.flush();
          clientSocket.close();
          continue;
        }


        final InputStream inputStreamServer = serverSocket.getInputStream();
        final OutputStream outputStreamServer = serverSocket.getOutputStream();

        // sending fake proxy session start bytes
        outputStreamClient.write("\u0000Z�\u001D  ".getBytes());
        outputStreamClient.flush();

        Thread workerThread = new Thread() {
          public void run() {
            int bytesRead;
            try {
              while ((bytesRead = inputStreamClient.read(request)) != -1) {
                outputStreamServer.write(request, 0, bytesRead);
                outputStreamServer.flush();
              }
            } catch (IOException e) {
            }

            try {
              outputStreamServer.close();
            } catch (IOException e) {
            }
          }
        };

        workerThread.start();

        int bytesRead;
        try {
          while ((bytesRead = inputStreamServer.read(reply)) != -1) {
            outputStreamClient.write(reply, 0, bytesRead);
            outputStreamClient.flush();
          }
        } catch (IOException e) {
        }
        
        outputStreamClient.close();
      } catch (IOException e) {
        System.err.println(e);
      } finally {
        try {
          if (serverSocket != null)
            serverSocket.close();
          if (clientSocket != null)
            clientSocket.close();
        } catch (IOException e) {
        }
      }
    }
  }
}
