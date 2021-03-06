package com.inmobi.messaging.netty;

/*
 * #%L
 * messaging-client-scribe
 * %%
 * Copyright (C) 2012 - 2014 InMobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.jboss.netty.channel.socket.*;
import org.jboss.netty.channel.socket.nio.*;

import java.util.concurrent.*;

class NettyEventCore {
  private static final NettyEventCore ourInstance = new NettyEventCore();

  private ClientSocketChannelFactory factory = null;
  private int leases = 0;

  public static NettyEventCore getInstance() {
    return ourInstance;
  }

  private NettyEventCore() {
  }

  /**
   * Get a handle to the netty event loop
   *
   * The NIO handlers are setup lazily
   *
   * @return an NIO handler
   */
  public synchronized ClientSocketChannelFactory getFactory() {
    if (factory == null) {
      factory = new NioClientSocketChannelFactory(
          Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
    }
    leases++;
    return factory;
  }

  /**
   * Application indicating that it no longer needs this
   *
   * In real world, assume that this will not be released, we are being nice and
   * try and release it.
   */
  public synchronized void releaseFactory() {
    if (factory != null) {
      leases--;
      if (leases == 0) {
        factory.releaseExternalResources();
        factory = null;
      }
    } else {
      // WTF! releasing what you did not take
    }
  }
}