/*
 * Copyright 2011 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nodex.java.core.http;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.ChannelGroupFutureListener;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameDecoder;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrameEncoder;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.nodex.java.core.EventHandler;
import org.nodex.java.core.Nodex;
import org.nodex.java.core.internal.NodexInternal;
import org.nodex.java.core.internal.SSLBase;
import org.nodex.java.core.internal.ThreadSourceUtils;

import javax.net.ssl.SSLEngine;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.ORIGIN;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_KEY1;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_KEY2;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_LOCATION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_ORIGIN;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.SEC_WEBSOCKET_PROTOCOL;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.WEBSOCKET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * <p>An HTTP server.</p>
 *
 * <p>The server supports both HTTP requests and HTML5 websockets and passes these to the user via the appropriate handlers.</p>
 *
 * <p>An {@code HttpServer} instance can only be used from the event loop that created it.</p>
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class HttpServer extends SSLBase {

  private EventHandler<HttpServerRequest> requestHandler;
  private EventHandler<Websocket> wsHandler;
  private Map<Channel, ServerConnection> connectionMap = new ConcurrentHashMap();
  private Map<String, Object> connectionOptions = new HashMap();
  private ChannelGroup serverChannelGroup;
  private boolean listening;
  private ClientAuth clientAuth = ClientAuth.NONE;
  private final Thread th;
  private final long contextID;

  /**
   * Create an {@code HttpServer}
   */
  public HttpServer() {
    Long cid = Nodex.instance.getContextID();
    if (cid == null) {
      throw new IllegalStateException("HTTPServer can only be used from an event loop");
    }
    this.contextID = cid;
    this.th = Thread.currentThread();

    //Defaults
    connectionOptions.put("child.tcpNoDelay", true);
    connectionOptions.put("child.keepAlive", true);
    connectionOptions.put("reuseAddress", true); //Not child since applies to the acceptor socket
  }

  /**
   * Set the request handler for the server to {@code requestHandler}. As HTTP requests are received by the server,
   * instances of {@link HttpServerRequest} will be created and passed to this handler.
   * @return a reference to this, so methods can be chained.
   */
  public HttpServer requestHandler(EventHandler<HttpServerRequest> requestHandler) {
    checkThread();
    this.requestHandler = requestHandler;
    return this;
  }

  /**
   * Set the websocket handler for the server to {@code wsHandler}. If a websocket connect handshake is successful a
   * new {@link Websocket} instance will be created and passed to the handler.
   * @return a reference to this, so methods can be chained.
   */
  public HttpServer websocketHandler(EventHandler<Websocket> wsHandler) {
    checkThread();
    this.wsHandler = wsHandler;
    return this;
  }

  /**
   * Tell the server to start listening on all interfaces and port {@code port}
   * @return a reference to this, so methods can be chained.
   */
  public HttpServer listen(int port) {
    return listen(port, "0.0.0.0");
  }

  /**
   * Tell the server to start listening on port {@code port} and host / ip address given by {@code host}.
   * @return a reference to this, so methods can be chained.
   */
  public HttpServer listen(int port, String host) {
    checkThread();

    if (requestHandler == null && wsHandler == null) {
      throw new IllegalStateException("Set request or websocket handler first");
    }
    if (listening) {
      throw new IllegalStateException("Listen already called");
    }

    listening = true;

    serverChannelGroup = new DefaultChannelGroup("nodex-acceptor-channels");
    ChannelFactory factory =
        new NioServerSocketChannelFactory(
            NodexInternal.instance.getAcceptorPool(),
            NodexInternal.instance.getWorkerPool());
    ServerBootstrap bootstrap = new ServerBootstrap(factory);
    bootstrap.setOptions(connectionOptions);

    checkSSL();

    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      public ChannelPipeline getPipeline() {
        ChannelPipeline pipeline = Channels.pipeline();

        if (ssl) {
          SSLEngine engine = context.createSSLEngine();
          engine.setUseClientMode(false);
          switch (clientAuth) {
            case REQUEST: {
              engine.setWantClientAuth(true);
              break;
            }
            case REQUIRED: {
              engine.setNeedClientAuth(true);
              break;
            }
            case NONE: {
              engine.setNeedClientAuth(false);
              break;
            }
          }
          pipeline.addLast("ssl", new SslHandler(engine));
        }

        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new HttpResponseEncoder());

        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());       // For large file / sendfile support
        pipeline.addLast("handler", new ServerHandler());
        return pipeline;
      }
    });

    try {
      Channel serverChannel = bootstrap.bind(new InetSocketAddress(InetAddress.getByName(host), port));
      serverChannelGroup.add(serverChannel);
    } catch (UnknownHostException e) {
      e.printStackTrace();
    }
    return this;
  }

  /**
   * If {@code ssl} is {@code true}, this signifies the server will handle SSL connections
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setSSL(boolean ssl) {
    checkThread();
    this.ssl = ssl;
    return this;
  }

  /**
   * Set the path to the SSL server key store. This method should only be used with the server in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The SSL server key store is a standard Java Key Store, and should contain the server certificate. A key store
   * must be provided for SSL to be operational.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setKeyStorePath(String path) {
    checkThread();
    this.keyStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL server key store. This method should only be used with the server in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setKeyStorePassword(String pwd) {
    checkThread();
    this.keyStorePassword = pwd;
    return this;
  }

  /**
   * Set the path to the SSL server trust store. This method should only be used with the server in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The SSL server trust store is a standard Java Key Store, and should contain the certificate(s) of the clients that the server trusts. The SSL
   * handshake will fail if the server requires client authentication and provides a certificate that the server does not trust.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setTrustStorePath(String path) {
    checkThread();
    this.trustStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL server trust store. This method should only be used with the server in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setTrustStorePassword(String pwd) {
    checkThread();
    this.trustStorePassword = pwd;
    return this;
  }

  /**
   * Set {@code required} to true if you want the server to request client authentication from any connecting clients. This
   * is an extra level of security in SSL, and requires clients to provide client certificates. Those certificates must be added
   * to the server trust store.
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpServer setClientAuthRequired(boolean required) {
    checkThread();
    clientAuth = required ? ClientAuth.REQUIRED : ClientAuth.NONE;
    return this;
  }

  /**
   * Close the server. Any open HTTP connections will be closed.
   */
  public void close() {
    checkThread();
    close(null);
  }

  /**
   * Close the server. Any open HTTP connections will be closed. {@code doneHandler} will be called when the close
   * is complete.
   */
  public void close(final EventHandler<Void> doneHandler) {
    checkThread();
    for (ServerConnection conn : connectionMap.values()) {
      conn.internalClose();
    }
    if (doneHandler != null) {
      serverChannelGroup.close().addListener(new ChannelGroupFutureListener() {
        public void operationComplete(ChannelGroupFuture channelGroupFuture) throws Exception {
          NodexInternal.instance.executeOnContext(contextID, new Runnable() {
            public void run() {
              doneHandler.onEvent(null);
            }
          });
        }
      });
    }
  }

  protected void checkThread() {
    // All ops must always be invoked on same thread
    if (Thread.currentThread() != th) {
      throw new IllegalStateException("Invoked with wrong thread, actual: " + Thread.currentThread() + " expected: " + th);
    }
  }

  public class ServerHandler extends SimpleChannelUpstreamHandler {

    private void calcAndWriteWSHandshakeResponse(Channel ch, HttpRequest request, long c) {
      String key1 = request.getHeader(SEC_WEBSOCKET_KEY1);
      String key2 = request.getHeader(SEC_WEBSOCKET_KEY2);
      ChannelBuffer output = WebsocketHandshakeHelper.calcResponse(key1, key2, c);
      HttpResponse res = new DefaultHttpResponse(HTTP_1_1, new HttpResponseStatus(101,
          "Web Socket Protocol Handshake"));
      res.setContent(output);
      res.addHeader(HttpHeaders.Names.CONTENT_LENGTH, res.getContent().readableBytes());
      res.addHeader(SEC_WEBSOCKET_ORIGIN, request.getHeader(ORIGIN));
      res.addHeader(SEC_WEBSOCKET_LOCATION, getWebSocketLocation(request, request.getUri()));
      String protocol = request.getHeader(SEC_WEBSOCKET_PROTOCOL);
      if (protocol != null) {
        res.addHeader(SEC_WEBSOCKET_PROTOCOL, protocol);
      }
      res.addHeader(HttpHeaders.Names.UPGRADE, WEBSOCKET);
      res.addHeader(CONNECTION, HttpHeaders.Values.UPGRADE);

      ChannelPipeline p = ch.getPipeline();
      p.replace("decoder", "wsdecoder", new WebSocketFrameDecoder());
      ch.write(res);
      p.replace("encoder", "wsencoder", new WebSocketFrameEncoder());
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
      NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      Object msg = e.getMessage();
      ServerConnection conn = connectionMap.get(ch);

      if (conn != null) {
        if (msg instanceof HttpRequest) {
          HttpRequest request = (HttpRequest) msg;

          if (HttpHeaders.is100ContinueExpected(request)) {
            ch.write(new DefaultHttpResponse(HTTP_1_1, CONTINUE));
          }

          if (HttpHeaders.Values.UPGRADE.equalsIgnoreCase(request.getHeader(CONNECTION)) &&
              WEBSOCKET.equalsIgnoreCase(request.getHeader(HttpHeaders.Names.UPGRADE))) {
            // Websocket handshake
            Websocket ws = new Websocket(request.getUri(), conn);

            boolean containsKey1 = request.containsHeader(SEC_WEBSOCKET_KEY1);
            boolean containsKey2 = request.containsHeader(SEC_WEBSOCKET_KEY2);

            if (containsKey1 && containsKey2) {
              long c = request.getContent().readLong();
              calcAndWriteWSHandshakeResponse(ch, request, c);

              conn.handleWebsocketConnect(ws);
            } else {
              ch.write(new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
            }
          } else {
            conn.handleMessage(msg);
          }
        } else {
          conn.handleMessage(msg);
        }
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
        throws Exception {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ServerConnection conn = connectionMap.get(ch);
      ch.close();
      final Throwable t = e.getCause();
      if (conn != null && t instanceof Exception) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            conn.handleException((Exception) t);
          }
        });
      } else {
        t.printStackTrace();
      }
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final long contextID = NodexInternal.instance.associateContextWithWorker(ch.getWorker());
      ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
        public void run() {
          final ServerConnection conn = new ServerConnection(ch, contextID, Thread.currentThread());
          conn.requestHandler(requestHandler);
          conn.wsHandler(wsHandler);
          connectionMap.put(ch, conn);
        }
      });
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ServerConnection conn = connectionMap.remove(ch);
      ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
        public void run() {
          conn.handleClosed();
          NodexInternal.instance.destroyContext(conn.getContextID());
        }
      });

    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ServerConnection conn = connectionMap.get(ch);
      ChannelState state = e.getState();
      if (state == ChannelState.INTEREST_OPS) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            conn.handleInterestedOpsChanged();
          }
        });
      }
    }

    private String getWebSocketLocation(HttpRequest req, String path) {
      return "ws://" + req.getHeader(HttpHeaders.Names.HOST) + path;
    }
  }
}
