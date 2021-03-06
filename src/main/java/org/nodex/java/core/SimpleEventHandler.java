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

package org.nodex.java.core;

/**
 * <p>This class can be used for simple event handlers which don't receive an event with any value.</p>
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public abstract class SimpleEventHandler implements EventHandler<Void> {

  public void onEvent(Void event) {
    onEvent();
  }

  /**
   * This method will be called when the event occurs. It should be overridden by the user.
   */
  protected abstract void onEvent();
}
