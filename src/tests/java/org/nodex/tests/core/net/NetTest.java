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

package org.nodex.tests.core.net;

import org.nodex.java.core.EventHandler;
import org.nodex.java.core.Nodex;
import org.nodex.java.core.NodexMain;
import org.nodex.java.core.SimpleEventHandler;
import org.nodex.java.core.buffer.Buffer;
import org.nodex.java.core.net.NetClient;
import org.nodex.java.core.net.NetServer;
import org.nodex.java.core.net.NetSocket;
import org.nodex.tests.Utils;
import org.nodex.tests.core.TestBase;
import org.testng.annotations.Test;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class NetTest extends TestBase {

  @Test
  public void testConnect() throws Exception {

    final int connectCount = 10;
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicInteger totalConnectCount = new AtomicInteger(0);


    new NodexMain() {
      public void go() throws Exception {

        final NetServer server = new NetServer();

        final long actorId = Nodex.instance.registerHandler(new EventHandler<String>() {
          public void onEvent(String msg) {

            server.close(new SimpleEventHandler() {
              public void onEvent() {
                latch.countDown();
              }
            });
          }
        });

        server.connectHandler(new EventHandler<NetSocket>() {
          public void onEvent(NetSocket sock) {
            if (totalConnectCount.incrementAndGet() == 2 * connectCount) {
              Nodex.instance.sendToHandler(actorId, "foo");
            }
          }
        }).listen(8181);


        NetClient client = new NetClient();

        for (int i = 0; i < connectCount; i++) {
          client.connect(8181, new EventHandler<NetSocket>() {
            public void onEvent(NetSocket sock) {
              sock.close();
              if (totalConnectCount.incrementAndGet() == 2 * connectCount) {
                Nodex.instance.sendToHandler(actorId, "foo");
              }
            }
          });
        }
      }
    }.run();

    azzert(latch.await(5, TimeUnit.SECONDS));

    throwAssertions();
  }

  @Test
  /*
  Test setting all the server params
  Actually quite hard to test this meaningfully
   */
  public void testServerParams() throws Exception {
    //TODO
  }

  @Test
  /*
  Test setting all the client params
  Actually quite hard to test this meaningfully
   */
  public void testClientParams() throws Exception {
    //TODO
  }

  @Test
  public void testCloseHandlerCloseFromClient() throws Exception {
    testCloseHandler(true);
  }

  @Test
  public void testCloseHandlerCloseFromServer() throws Exception {
    testCloseHandler(false);
  }

  @Test
  public void testSendDataClientToServerString() throws Exception {
    testSendData(true, true);
  }

  @Test
  public void testSendDataServerToClientString() throws Exception {
    testSendData(false, true);
  }

  @Test
  public void testSendDataClientToServerBytes() throws Exception {
    testSendData(true, false);
  }

  @Test
  public void testSendDataServerToClientBytes() throws Exception {
    testSendData(false, false);
  }

  @Test
  /*
  Test writing with a completion
   */
  public void testWriteWithCompletion() throws Exception {

    final CountDownLatch latch = new CountDownLatch(1);
    final int numSends = 10;
    final int sendSize = 100;

    new NodexMain() {
      public void go() throws Exception {

        final NetServer server = new NetServer();

        final Buffer sentBuff = Buffer.create(0);
        final Buffer receivedBuff = Buffer.create(0);

        final long actorId = Nodex.instance.registerHandler(new EventHandler<String>() {
          public void onEvent(String msg) {
            azzert(Utils.buffersEqual(sentBuff, receivedBuff));
            server.close(new SimpleEventHandler() {
              public void onEvent() {
                latch.countDown();
              }
            });
          }
        });

        server.connectHandler(new EventHandler<NetSocket>() {
          public void onEvent(NetSocket sock) {
            sock.dataHandler(new EventHandler<Buffer>() {
              public void onEvent(Buffer data) {
                receivedBuff.appendBuffer(data);
                if (receivedBuff.length() == numSends * sendSize) {
                  Nodex.instance.sendToHandler(actorId, "foo");
                }
              }
            });
          }
        }).listen(8181);

        NetClient client = new NetClient().connect(8181, new EventHandler<NetSocket>() {
          public void onEvent(NetSocket sock) {
            final ContextChecker checker = new ContextChecker();
            doWrite(sentBuff, sock, numSends, sendSize, checker);
          }
        });
      }
    }.run();


    azzert(latch.await(5, TimeUnit.SECONDS));

    throwAssertions();
  }

  @Test
  public void testSendFileClientToServer() throws Exception {
    testSendFile(true);
  }

  @Test
  public void testSendFileServerToClient() throws Exception {
    testSendFile(false);
  }

  private void testSendFile(final boolean clientToServer) throws Exception {
    final String path = "foo.txt";

    final String content = Utils.randomAlphaString(10000);
    final File file = setupFile(path, content);
    final CountDownLatch latch = new CountDownLatch(1);

    new NodexMain() {
      public void go() throws Exception {
        EventHandler<NetSocket> sender = new EventHandler<NetSocket>() {
          public void onEvent(final NetSocket sock) {
            String fileName = "./" + path;
            sock.sendFile(fileName);
          }
        };

        final NetServer server = new NetServer();

        final long actorId = Nodex.instance.registerHandler(new EventHandler<String>() {
          public void onEvent(String msg) {
            server.close(new SimpleEventHandler() {
              public void onEvent() {
                latch.countDown();
              }
            });
          }
        });

        EventHandler<NetSocket> receiver = new EventHandler<NetSocket>() {
          public void onEvent(NetSocket sock) {
            final Buffer buff = Buffer.create(0);
            sock.dataHandler(new EventHandler<Buffer>() {
              public void onEvent(final Buffer data) {
                buff.appendBuffer(data);
                if (buff.length() == file.length()) {
                  azzert(content.equals(buff.toString()));
                  Nodex.instance.sendToHandler(actorId, "foo");
                }
              }
            });
          }
        };

        EventHandler<NetSocket> serverHandler = clientToServer ? receiver : sender;
        EventHandler<NetSocket> clientHandler = clientToServer ? sender : receiver;

        server.connectHandler(serverHandler).listen(8181);
        new NetClient().connect(8181, clientHandler);
      }
    }.run();

    assert latch.await(5, TimeUnit.SECONDS);
    throwAssertions();
    file.delete();
  }

  //Recursive - we don't write the next packet until we get the completion back from the previous write
  private void doWrite(final Buffer sentBuff, final NetSocket sock, int count, final int sendSize,
                       final ContextChecker checker) {
    Buffer b = Utils.generateRandomBuffer(sendSize);
    sentBuff.appendBuffer(b);
    count--;
    final int c = count;
    if (count == 0) {
      sock.write(b);
    } else {
      sock.write(b, new SimpleEventHandler() {
        public void onEvent() {
          checker.check();
          doWrite(sentBuff, sock, c, sendSize, checker);
        }
      });
    }
  }

  private void testSendData(final boolean clientToServer, final boolean string) throws Exception {

    final CountDownLatch latch = new CountDownLatch(1);
    final int numSends = 10;
    final int sendSize = 100;

    new NodexMain() {
      public void go() throws Exception {

        final NetServer server = new NetServer();

        final Buffer sentBuff = Buffer.create(0);
        final Buffer receivedBuff = Buffer.create(0);

        final long actorId = Nodex.instance.registerHandler(new EventHandler<String>() {
          public void onEvent(String msg) {
            azzert(Utils.buffersEqual(sentBuff, receivedBuff));
            server.close(new SimpleEventHandler() {
              public void onEvent() {
                latch.countDown();
              }
            });
          }
        });

        EventHandler<NetSocket> receiver = new EventHandler<NetSocket>() {
          public void onEvent(final NetSocket sock) {
            final ContextChecker checker = new ContextChecker();
            sock.dataHandler(new EventHandler<Buffer>() {
              public void onEvent(Buffer data) {
                checker.check();
                receivedBuff.appendBuffer(data);
                if (receivedBuff.length() == numSends * sendSize) {
                  sock.close();
                  Nodex.instance.sendToHandler(actorId, "foo");
                }
              }
            });
          }
        };

        EventHandler<NetSocket> sender = new EventHandler<NetSocket>() {
          public void onEvent(NetSocket sock) {
            for (int i = 0; i < numSends; i++) {
              if (string) {
                byte[] bytes = new byte[sendSize];
                Arrays.fill(bytes, (byte) 'X');
                try {
                  String s = new String(bytes, "UTF-8");
                  sentBuff.appendBytes(bytes);
                  sock.write(s);
                } catch (Exception e) {
                  e.printStackTrace();
                }
              } else {
                Buffer b = Utils.generateRandomBuffer(sendSize);
                sentBuff.appendBuffer(b);
                sock.write(b);
              }
            }
          }
        };
        EventHandler<NetSocket> serverHandler = clientToServer ? receiver : sender;
        EventHandler<NetSocket> clientHandler = clientToServer ? sender : receiver;

        server.connectHandler(serverHandler).listen(8181);
        new NetClient().connect(8181, clientHandler);
      }
    }.run();

    azzert(latch.await(5, TimeUnit.SECONDS));
    throwAssertions();
  }


  private void testCloseHandler(final boolean closeClient) throws Exception {
    final int connectCount = 10;

    final AtomicInteger clientCloseCount = new AtomicInteger(0);
    final AtomicInteger serverCloseCount = new AtomicInteger(0);
    final CountDownLatch clientCloseLatch = new CountDownLatch(1);
    final CountDownLatch serverCloseLatch = new CountDownLatch(1);

    new NodexMain() {
      public void go() throws Exception {

        final NetServer server = new NetServer();

        final long actorId = Nodex.instance.registerHandler(new EventHandler<String>() {
          public void onEvent(String msg) {
            server.close(new SimpleEventHandler() {
              public void onEvent() {
                serverCloseLatch.countDown();
              }
            });
          }
        });

        server.connectHandler(new EventHandler<NetSocket>() {
          public void onEvent(final NetSocket sock) {
            final ContextChecker checker = new ContextChecker();
            sock.closedHandler(new SimpleEventHandler() {
              public void onEvent() {
                checker.check();
                if (serverCloseCount.incrementAndGet() == connectCount) {
                  Nodex.instance.sendToHandler(actorId, "foo");
                }
              }
            });
            if (!closeClient) sock.close();
          }
        }).listen(8181);

        NetClient client = new NetClient();

        for (int i = 0; i < connectCount; i++) {
          client.connect(8181, new EventHandler<NetSocket>() {
            public void onEvent(NetSocket sock) {
              final ContextChecker checker = new ContextChecker();
              sock.closedHandler(new SimpleEventHandler() {
                public void onEvent() {
                  checker.check();
                  if (clientCloseCount.incrementAndGet() == connectCount) {
                    clientCloseLatch.countDown();
                  }
                }
              });
              if (closeClient) sock.close();
            }
          });
        }
      }
    }.run();

    azzert(serverCloseLatch.await(5, TimeUnit.SECONDS));
    azzert(clientCloseLatch.await(5, TimeUnit.SECONDS));
    throwAssertions();
  }
}
