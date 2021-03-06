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

package org.nodex.tests.core;

import org.nodex.java.core.EventHandler;
import org.nodex.java.core.internal.NodexInternal;
import org.nodex.java.core.shared.SharedData;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ActorTest extends TestBase {

  private NodexInternal nodex = NodexInternal.instance;

  @Test
  public void testActor() throws Exception {

    final String message = "Hello actor";

    final Map<String, Long> map = SharedData.getMap("foo");

    final CountDownLatch latch1 = new CountDownLatch(1);
    final CountDownLatch latch2 = new CountDownLatch(1);
    final long contextID1 = nodex.createAndAssociateContext();
    nodex.executeOnContext(contextID1, new Runnable() {
      public void run() {
        nodex.setContextID(contextID1);
        long actorID = nodex.registerHandler(new EventHandler<String>() {
          public void onEvent(String message) {
            azzert(contextID1 == nodex.getContextID());
            nodex.unregisterHandler(map.get("actorid"));
            latch2.countDown();
          }
        });
        map.put("actorid", actorID);
        latch1.countDown();
      }
    });

    azzert(latch1.await(5, TimeUnit.SECONDS));

    final long contextID2 = nodex.createAndAssociateContext();
    nodex.executeOnContext(contextID2, new Runnable() {
      public void run() {
        nodex.setContextID(contextID2);
        //Send msg to actor
        long actorID = map.get("actorid");
        nodex.<String>sendToHandler(actorID, message);
      }
    });

    azzert(latch2.await(5, TimeUnit.SECONDS));
    nodex.destroyContext(contextID1);
    nodex.destroyContext(contextID2);

    throwAssertions();
  }


  @Test
  public void testActorNoContext() throws Exception {

    try {
      nodex.registerHandler(new EventHandler<String>() {
        public void onEvent(String message) {
        }
      });
      azzert(false);
    } catch (IllegalStateException e) {
      //Expected
    }

    throwAssertions();
  }

}
