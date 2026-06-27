// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.SubscriberExceptionHandler;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-command {@link EventBus} that remembers its registered subscribers so they can all be
 * dropped at command end via {@link #unregisterAll}.
 *
 * <p>Guava's {@link EventBus} holds every registered subscriber strongly through its internal
 * {@code SubscriberRegistry}, and the bus is reachable past the command (it is captured by
 * long-lived subsystems through the command's {@code Reporter}, and held in {@code final} fields of
 * {@code CommandEnvironment} and {@code UiEventHandler}). As a result the bus, all its subscribers,
 * and everything those subscribers reference (the whole command-scoped object graph) would survive
 * for the server's lifetime. Emptying the registry at command end breaks the bus -&gt; subscriber
 * edges, so the command's object graph becomes collectable. This avoids having to chase every
 * individual subsystem that captures command-scoped state.
 */
final class CommandEventBus extends EventBus {
  private final Set<Object> registered = Collections.newSetFromMap(new ConcurrentHashMap<>());

  CommandEventBus(SubscriberExceptionHandler exceptionHandler) {
    super(exceptionHandler);
  }

  @Override
  public void register(Object object) {
    super.register(object);
    registered.add(object);
  }

  @Override
  public void unregister(Object object) {
    registered.remove(object);
    super.unregister(object);
  }

  /** Unregisters every subscriber still registered, so the bus stops retaining them. */
  void unregisterAll() {
    for (Object object : registered) {
      try {
        super.unregister(object);
      } catch (IllegalArgumentException e) {
        // Already unregistered; ignore.
      }
    }
    registered.clear();
  }
}
