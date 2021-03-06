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

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioSocketChannel;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkTrailer;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrame;
import org.jboss.netty.handler.ssl.SslHandler;
import org.nodex.java.core.EventHandler;
import org.nodex.java.core.Nodex;
import org.nodex.java.core.internal.NodexInternal;
import org.nodex.java.core.internal.SSLBase;
import org.nodex.java.core.SimpleEventHandler;
import org.nodex.java.core.internal.ThreadSourceUtils;
import org.nodex.java.core.buffer.Buffer;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>An asynchronous, pooling, HTTP 1.1 client</p>
 *
 * <p>An {@code HttpClient} maintains a pool of connections to a specific host, at a specific port. The HTTP connections can act
 * as pipelines for HTTP requests.</p>
 * <p>It is used as a factory for {@link HttpClientRequest} instances which encapsulate the actual HTTP requests. It is also
 * used as a factory for HTML5 {@link Websocket websockets}.</p>
 * <p>The client is thread-safe and can be safely shared my different event loops.</p>
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class HttpClient extends SSLBase {

  private ClientBootstrap bootstrap;
  private NioClientSocketChannelFactory channelFactory;
  private Map<Channel, ClientConnection> connectionMap = new ConcurrentHashMap();
  private EventHandler<Exception> exceptionHandler;
  private int port = 80;
  private String host = "localhost";
  private final Queue<ClientConnection> available = new ConcurrentLinkedQueue<ClientConnection>();
  private int maxPoolSize = 1;
  private final AtomicInteger connectionCount = new AtomicInteger(0);
  private final Queue<Waiter> waiters = new ConcurrentLinkedQueue<Waiter>();
  private boolean keepAlive = true;

  /**
   * Create an {@code HttpClient} instance
   */
  public HttpClient() {
  }

  /**
   * Set the exception handler for the {@code HttpClient}
   */
  public void exceptionHandler(EventHandler<Exception> handler) {
    this.exceptionHandler = handler;
  }

  /**
   * Set the maximum pool size to the value specified by {@code maxConnections}<p>
   * The client will maintain up to {@code maxConnections} HTTP connections in an internal pool<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setMaxPoolSize(int maxConnections) {
    this.maxPoolSize = maxConnections;
    return this;
  }

  /**
   * Returns the maximum number of connections in the pool
   */
  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  /**
   * If {@code keepAlive} is {@code true} then, after the request has ended the connection will be returned to the pool
   * where it can be used by another request. In this manner, many HTTP requests can be pipe-lined over an HTTP connection.
   * Keep alive connections will not be closed until the {@link #close() close()} method is invoked.<p>
   * If {@code keepAlive} is {@code false} then a new connection will be created for each request and it won't ever go in the pool,
   * the connection will closed after the response has been received. Even with no keep alive, the client will not allow more
   * than {@link #getMaxPoolSize() getMaxPoolSize()} connections to be created at any one time. <p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setKeepAlive(boolean keepAlive) {
    this.keepAlive = keepAlive;
    return this;
  }

  /**
   * If {@code ssl} is {@code true}, this signifies the client will create SSL connections
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setSSL(boolean ssl) {
    this.ssl = ssl;
    return this;
  }

  /**
   * Set the path to the SSL client key store. This method should only be used with the client in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The SSL client key store is a standard Java Key Store, and should contain the client certificate. It's only necessary to supply
   * a client key store if the server requires client authentication via client certificates.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setKeyStorePath(String path) {
    this.keyStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL client key store. This method should only be used with the client in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setKeyStorePassword(String pwd) {
    this.keyStorePassword = pwd;
    return this;
  }

  /**
   * Set the path to the SSL client trust store. This method should only be used with the client in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * The SSL client trust store is a standard Java Key Store, and should contain the certificate(s) of the servers that the client trusts. The SSL
   * handshake will fail if the server provides a certificate that the client does not trust.<p>
   * If you wish the client to trust all server certificates you can use the {@link #setTrustAll(boolean)} method.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setTrustStorePath(String path) {
    this.trustStorePath = path;
    return this;
  }

  /**
   * Set the password for the SSL client trust store. This method should only be used with the client in SSL mode, i.e. after {@link #setSSL(boolean)}
   * has been set to {@code true}.<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setTrustStorePassword(String pwd) {
    this.trustStorePassword = pwd;
    return this;
  }

  /**
   * If {@code trustAll} is set to {@code true} then the client will trust ALL server certifactes and will not attempt to authenticate them
   * against it's local client trust store.<p>
   * Use this method with caution!
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setTrustAll(boolean trustAll) {
    this.trustAll = trustAll;
    return this;
  }

  /**
   * Set the port that the client will attempt to connect to on the server to {@code port}. The default value is {@code 80}<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setPort(int port) {
    this.port = port;
    return this;
  }

  /**
   * Set the host that the client will attempt to connect to, to {@code host}. The default value is {@code localhost}<p>
   * @return A reference to this, so multiple invocations can be chained together.
   */
  public HttpClient setHost(String host) {
    this.host = host;
    return this;
  }

  /**
   * Attempt to connect an HTML5 websocket to the specified URI<p>
   * The connect is done asynchronously and {@code wsConnect} is called back with the result
   */
  public void connectWebsocket(final String uri, final EventHandler<Websocket> wsConnect) {
    getConnection(new EventHandler<ClientConnection>() {
      public void onEvent(final ClientConnection conn) {
        conn.toWebSocket(uri, wsConnect);
      }
    }, Nodex.instance.getContextID());
  }

  /**
   * This is a quick version of the {@link #get(String, EventHandler) get()} method where you do not want to do anything with the request
   * before sending.<p>
   * Normally with any of the HTTP methods you create the request then when you are ready to send it you call
   * {@link HttpClientRequest#end()} on it. With this method the request is immediately sent.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public void getNow(String uri, EventHandler<HttpClientResponse> responseHandler) {
    getNow(uri, null, responseHandler);
  }

  /**
   * This method works in the same manner as {@link #getNow(String, EventHandler)},
   * except that it allows you specify a set of {@code headers} that will be sent with the request.
   */
  public void getNow(String uri, Map<String, ? extends Object> headers, EventHandler<HttpClientResponse> responseHandler) {
    HttpClientRequest req = get(uri, responseHandler);
    if (headers != null) {
      req.putAllHeaders(headers);
    }
    req.end();
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP OPTIONS request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest options(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("OPTIONS", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP GET request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest get(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("GET", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP HEAD request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest head(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("HEAD", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP POST request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest post(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("POST", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP PUT request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest put(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("PUT", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP DELETE request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest delete(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("DELETE", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP TRACE request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest trace(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("TRACE", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP CONNECT request with the specified {@code uri}.<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest connect(String uri, EventHandler<HttpClientResponse> responseHandler) {
    return request("CONNECT", uri, responseHandler);
  }

  /**
   * This method returns an {@link HttpClientRequest} instance which represents an HTTP request with the specified {@code uri}. The specific HTTP method
   * (e.g. GET, POST, PUT etc) is specified using the parameter {@code method}<p>
   * When an HTTP response is received from the server the {@code responseHandler} is called passing in the response.
   */
  public HttpClientRequest request(String method, String uri, EventHandler<HttpClientResponse> responseHandler) {
    final Long cid = Nodex.instance.getContextID();
    if (cid == null) {
      throw new IllegalStateException("Requests must be made from inside an event loop");
    }
    return new HttpClientRequest(this, method, uri, responseHandler, cid, Thread.currentThread());
  }

  /**
   * Close the HTTP client. This will cause any pooled HTTP connections to be closed.
   */
  public void close() {
    available.clear();
    if (!waiters.isEmpty()) {
      System.out.println("Warning: Closing HTTP client, but there are " + waiters.size() + " waiting for connections");
    }
    waiters.clear();
    for (ClientConnection conn : connectionMap.values()) {
      conn.internalClose();
    }
  }

  //TODO FIXME - heavyweight synchronization for now FIXME
  //This will be a contention point
  //Need to be improved

  synchronized void getConnection(EventHandler<ClientConnection> handler, long contextID) {
    ClientConnection conn = available.poll();
    if (conn != null) {
      handler.onEvent(conn);
    } else {
      if (connectionCount.get() < maxPoolSize) {
        if (connectionCount.incrementAndGet() <= maxPoolSize) {
          //Create new connection
          connect(handler, contextID);
          return;
        } else {
          connectionCount.decrementAndGet();
        }
      }
      // Add to waiters
      waiters.add(new Waiter(handler, contextID));
    }
  }

  synchronized void returnConnection(final ClientConnection conn) {
    if (!conn.keepAlive) {
      //Just close it
      conn.internalClose();
    } else {
      //Return it to the pool
      final Waiter waiter = waiters.poll();

      if (waiter != null) {
        NodexInternal.instance.executeOnContext(waiter.contextID, new Runnable() {
          public void run() {
            NodexInternal.instance.setContextID(waiter.contextID);
            waiter.handler.onEvent(conn);
          }
        });
      } else {
        available.add(conn);
      }
    }
  }

  private void connect(final EventHandler<ClientConnection> connectHandler, final long contextID) {

    if (bootstrap == null) {
      channelFactory = new NioClientSocketChannelFactory(
          NodexInternal.instance.getAcceptorPool(),
          NodexInternal.instance.getWorkerPool());
      bootstrap = new ClientBootstrap(channelFactory);

      checkSSL();

      bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
        public ChannelPipeline getPipeline() throws Exception {
          ChannelPipeline pipeline = Channels.pipeline();
          if (ssl) {
            SSLEngine engine = context.createSSLEngine();
            engine.setUseClientMode(true); //We are on the client side of the connection
            pipeline.addLast("ssl", new SslHandler(engine));
          }
          pipeline.addLast("encoder", new HttpRequestEncoder());
          pipeline.addLast("decoder", new HttpResponseDecoder());
          pipeline.addLast("handler", new ClientHandler());
          return pipeline;
        }
      });
    }

    //Client connections share context with caller
    channelFactory.setWorker(NodexInternal.instance.getWorkerForContextID(contextID));

    ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
    future.addListener(new ChannelFutureListener() {
      public void operationComplete(ChannelFuture channelFuture) throws Exception {
        if (channelFuture.isSuccess()) {

          final NioSocketChannel ch = (NioSocketChannel) channelFuture.getChannel();

          ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
            public void run() {
              final ClientConnection conn = new ClientConnection(HttpClient.this, ch,
                  host + ":" + port, ssl, keepAlive, contextID,
                  Thread.currentThread());
              conn.closedHandler(new SimpleEventHandler() {
                public void onEvent() {
                  if (connectionCount.decrementAndGet() < maxPoolSize) {
                    //Now the connection count has come down, maybe there is another waiter that can
                    //create a new connection
                    Waiter waiter = waiters.poll();
                    if (waiter != null) {
                      getConnection(waiter.handler, waiter.contextID);
                    }
                  }
                }
              });
              connectionMap.put(ch, conn);
              NodexInternal.instance.setContextID(contextID);
              connectHandler.onEvent(conn);
            }
          });
        } else {
          Throwable t = channelFuture.getCause();
          if (t instanceof Exception && exceptionHandler != null) {
            exceptionHandler.onEvent((Exception) t);
          } else {
            t.printStackTrace(System.err);
          }
        }
      }
    });
  }

  private static class Waiter {
    final EventHandler<ClientConnection> handler;
    final long contextID;

    private Waiter(EventHandler<ClientConnection> handler, long contextID) {
      this.handler = handler;
      this.contextID = contextID;
    }
  }

  private class ClientHandler extends SimpleChannelUpstreamHandler {

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ClientConnection conn = connectionMap.remove(ch);
      if (conn != null) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            conn.handleClosed();
          }
        });
      }
    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ClientConnection conn = connectionMap.get(ch);
      ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
        public void run() {
          conn.handleInterestedOpsChanged();
        }
      });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
      final NioSocketChannel ch = (NioSocketChannel) e.getChannel();
      final ClientConnection conn = connectionMap.get(ch);
      final Throwable t = e.getCause();
      if (conn != null && t instanceof Exception) {
        ThreadSourceUtils.runOnCorrectThread(ch, new Runnable() {
          public void run() {
            conn.handleException((Exception) t);
          }
        });
      } else {
        t.printStackTrace(System.err);
      }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

      Channel ch = e.getChannel();
      ClientConnection conn = connectionMap.get(ch);
      Object msg = e.getMessage();
      if (msg instanceof HttpResponse) {

        HttpResponse response = (HttpResponse) msg;

        conn.handleResponse(response);
        ChannelBuffer content = response.getContent();

        if (content.readable()) {
          conn.handleResponseChunk(new Buffer(content));
        }
        if (!response.isChunked()) {
          conn.handleResponseEnd();
        }
      } else if (msg instanceof HttpChunk) {
        HttpChunk chunk = (HttpChunk) msg;
        if (chunk.getContent().readable()) {
          Buffer buff = new Buffer(chunk.getContent());
          conn.handleResponseChunk(buff);
        }
        if (chunk.isLast()) {
          if (chunk instanceof HttpChunkTrailer) {
            HttpChunkTrailer trailer = (HttpChunkTrailer) chunk;
            conn.handleResponseEnd(trailer);
          } else {
            conn.handleResponseEnd();
          }
        }
      } else if (msg instanceof WebSocketFrame) {
        WebSocketFrame frame = (WebSocketFrame) msg;
        conn.handleWsFrame(frame);
      } else {
        throw new IllegalStateException("Invalid object " + e.getMessage());
      }
    }
  }
}
