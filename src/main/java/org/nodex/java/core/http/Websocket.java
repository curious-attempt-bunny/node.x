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

import org.jboss.netty.handler.codec.http.websocket.DefaultWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocket.WebSocketFrame;
import org.nodex.java.core.EventHandler;
import org.nodex.java.core.buffer.Buffer;
import org.nodex.java.core.streams.ReadStream;
import org.nodex.java.core.streams.WriteStream;

/**
 * <p>Encapsulation of an HTML 5 Websocket</p>
 *
 * <p>Instances of this class are either created by an {@link HttpServer}
 * instance when a websocket handshake is accepted on the server, or are create by an {@link HttpClient}
 * instance when a client succeeds in a websocket handshake with a server. Once an instance has been obtained it can
 * be used to send or receive buffers of data from the connection, a bit like a TCP socket.</p>
 *
 * <p>Instances of this class can only be used from the event loop thread which created it.</p>
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Websocket implements ReadStream, WriteStream {

  private final AbstractConnection conn;

  private EventHandler<Buffer> dataHandler;
  private EventHandler<Void> drainHandler;
  private EventHandler<Exception> exceptionHandler;
  private EventHandler<Void> endHandler;

  Websocket(String uri, AbstractConnection conn) {
    this.uri = uri;
    this.conn = conn;
  }

  /**
   * The uri the websocket was created on. When a websocket is first received on the server, the uri can be checked and
   * the websocket can be closed if you want to restrict which uris you wish to accept websockets on.
   */
  public final String uri;

  /**
   * Write {@code data} to the websocket as binary frame
   */
  public void writeBinaryFrame(Buffer data) {
    WebSocketFrame frame = new DefaultWebSocketFrame(0x80, data.getChannelBuffer());
    conn.write(frame);
  }

  /**
   * Write {@code str} to the websocket as text frame
   */
  public void writeTextFrame(String str) {
    WebSocketFrame frame = new DefaultWebSocketFrame(str);
    conn.write(frame);
  }

  /**
   * Specify a data handler for the websocket. As data is received on the websocket the handler will be called, passing
   * in a Buffer of data
   */
  public void dataHandler(EventHandler<Buffer> handler) {
    this.dataHandler = handler;
  }

  /**
   * Specify an end handler for the websocket. The {@code endHandler} is called once there is no more data to be read.
   */
  public void endHandler(EventHandler<Void> handler) {
    this.endHandler = handler;
  }

  /**
   * Specify an exception handler for the websocket. The {@code exceptionHandler} is called if an exception occurs.
   */
  public void exceptionHandler(EventHandler<Exception> handler) {
    this.exceptionHandler = handler;
  }

  /**
   * Pause the websocket. Once the websocket has been paused, the system will stop reading any more chunks of data
   * from the wire, thus pushing back to the server.
   * Pause is often used in conjunction with a {@link org.nodex.java.core.streams.Pump} to pump data between streams and implement flow control.
   */
  public void pause() {
    conn.pause();
  }

  /**
   * Resume a paused websocket. The websocket will resume receiving chunks of data from the wire.<p>
   * Resume is often used in conjunction with a {@link org.nodex.java.core.streams.Pump} to pump data between streams and implement flow control.
   */
  public void resume() {
    conn.resume();
  }

  /**
   * Data is queued until it is actually sent. To set the point at which the queue is considered "full" call this method
   * specifying the {@code maxSize} in bytes.<p>
   * This method is used by the {@link org.nodex.java.core.streams.Pump} class to pump data
   * between different streams and perform flow control.
   */
  public void setWriteQueueMaxSize(int maxSize) {
    conn.setWriteQueueMaxSize(maxSize);
  }

  /**
   * If the amount of data that is currently queued is greater than the write queue max size see {@link #setWriteQueueMaxSize(int)}
   * then the write queue is considered full.<p>
   * Data can still be written to the websocket even if the write queue is deemed full, however it should be used as indicator
   * to stop writing and push back on the source of the data, otherwise you risk running out of available RAM.<p>
   * This method is used by the {@link org.nodex.java.core.streams.Pump} class to pump data
   * between different streams and perform flow control.
   * @return {@code true} if the write queue is full, {@code false} otherwise
   */
  public boolean writeQueueFull() {
    return conn.writeQueueFull();
  }

  /**
   * Write a {@link Buffer} to the websocket.
   */
  public void writeBuffer(Buffer data) {
    writeBinaryFrame(data);
  }

  /**
   * This method sets a drain handler {@code handler} on the websocket. The drain handler will be called when write queue is no longer
   * full and it is safe to write to it again.<p>
   * The drain handler is actually called when the write queue size reaches <b>half</b> the write queue max size to prevent thrashing.
   * This method is used as part of a flow control strategy, e.g. it is used by the {@link org.nodex.java.core.streams.Pump} class to pump data
   * between different streams.
   * @param handler
   */
  public void drainHandler(EventHandler<Void> handler) {
    this.drainHandler = handler;
  }

  /**
   * Close the websocket
   */
  public void close() {
    conn.close();
  }

  void handleFrame(WebSocketFrame frame) {
    if (dataHandler != null) {
      dataHandler.onEvent(new Buffer(frame.getBinaryData()));
    }
  }

  void writable() {
    if (drainHandler != null) {
      drainHandler.onEvent(null);
    }
  }

  void handleException(Exception e) {
    if (exceptionHandler != null) {
      exceptionHandler.onEvent(e);
    }
  }
}
