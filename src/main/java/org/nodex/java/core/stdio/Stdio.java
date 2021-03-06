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

package org.nodex.java.core.stdio;

/**
 * Provides asynchronous versions of stdin and stdout and stderr
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Stdio {

  /**
   * An asynchronous version of {@link System#in}
   */
  public static final InStream in = new InStream(System.in);

  /**
   * An asynchronous version of {@link System#out}
   */
  public static final OutStream out = new OutStream(System.out);

  /**
   * An asynchronous version of {@link System#err}
   */
  public static final OutStream err = new OutStream(System.err);


}
