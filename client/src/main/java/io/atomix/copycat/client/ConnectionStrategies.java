/*
 * Copyright 2015 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.copycat.client;

import java.time.Duration;

/**
 * Basic connection strategies.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public enum ConnectionStrategies implements ConnectionStrategy {

  /**
   * Attempts to connect to the cluster one time and fails.
   */
  ONCE {
    @Override
    public void attemptFailed(Attempt attempt) {
      attempt.fail();
    }
  },

  /**
   * Attempts to connect to the cluster using exponential backoff up to a one minute retry interval.
   */
  EXPONENTIAL_BACKOFF {
    @Override
    public void attemptFailed(Attempt attempt) {
      attempt.retry(Duration.ofSeconds(Math.min(Math.round(Math.pow(attempt.attempt(), 2)), 60)));
    }
  },

  /**
   * Attempts to connect to the cluster using fibonacci sequence backoff.
   */
  FIBONACCI_BACKOFF {
    private final int[] FIBONACCI = new int[]{1, 1, 2, 3, 5, 8, 13};

    @Override
    public void attemptFailed(Attempt attempt) {
      attempt.retry(Duration.ofSeconds(FIBONACCI[Math.min(attempt.attempt()-1, FIBONACCI.length-1)]));
    }
  }

}
