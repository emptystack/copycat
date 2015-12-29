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
package io.atomix.copycat.client.session;

import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.Operation;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.client.RetryStrategy;
import io.atomix.copycat.client.error.CommandException;
import io.atomix.copycat.client.error.QueryException;
import io.atomix.copycat.client.request.CommandRequest;
import io.atomix.copycat.client.request.OperationRequest;
import io.atomix.copycat.client.request.QueryRequest;
import io.atomix.copycat.client.response.CommandResponse;
import io.atomix.copycat.client.response.OperationResponse;
import io.atomix.copycat.client.response.QueryResponse;
import io.atomix.copycat.client.response.Response;
import io.atomix.copycat.client.util.ClientSequencer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Session operation submitter.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ClientSessionSubmitter {
  private final Connection connection;
  private final ClientSessionState state;
  private final ThreadContext context;
  private final RetryStrategy strategy;
  private final ClientSequencer sequencer = new ClientSequencer();
  private final Map<Long, OperationAttempt> attempts = new HashMap<>();
  private final Map<Long, Scheduled> retries = new HashMap<>();
  private boolean open;

  public ClientSessionSubmitter(Connection connection, ClientSessionState state, ThreadContext context, RetryStrategy retryStrategy) {
    this.connection = Assert.notNull(connection, "connection");
    this.state = Assert.notNull(state, "state");
    this.context = Assert.notNull(context, "context");
    this.strategy = Assert.notNull(retryStrategy, "retryStrategy");
  }

  /**
   * Submits a command to the cluster.
   *
   * @param command The command to submit.
   * @param <T> The command result type.
   * @return A completable future to be completed once the command has been submitted.
   */
  public <T> CompletableFuture<T> submit(Command<T> command) {
    CompletableFuture<T> future = new CompletableFuture<>();
    context.executor().execute(() -> submitCommand(command, future));
    return future;
  }

  /**
   * Submits a command to the cluster.
   */
  private <T> void submitCommand(Command<T> command, CompletableFuture<T> future) {
    CommandRequest request = CommandRequest.builder()
      .withSession(state.getSessionId())
      .withSequence(state.nextCommandRequest())
      .withCommand(command)
      .build();
    submitCommand(request, future);
  }

  /**
   * Submits a command request to the cluster.
   */
  private <T> void submitCommand(CommandRequest request, CompletableFuture<T> future) {
    CommandAttempt<T> attempt = new CommandAttempt<>(sequencer.nextSequence(), request, future);
    attempts.put(attempt.sequence, attempt);
    submit(attempt);
  }

  /**
   * Submits a query to the cluster.
   *
   * @param query The query to submit.
   * @param <T> The query result type.
   * @return A completable future to be completed once the query has been submitted.
   */
  public <T> CompletableFuture<T> submit(Query<T> query) {
    CompletableFuture<T> future = new CompletableFuture<>();
    context.executor().execute(() -> submitQuery(query, future));
    return future;
  }

  /**
   * Submits a query to the cluster.
   */
  private <T> void submitQuery(Query<T> query, CompletableFuture<T> future) {
    if (query.consistency() == Query.ConsistencyLevel.CAUSAL) {
      QueryRequest request = QueryRequest.builder()
        .withSession(state.getSessionId())
        .withSequence(state.getCommandResponse())
        .withIndex(state.getResponseIndex())
        .withQuery(query)
        .build();
      submitQuery(request, future);
    } else {
      QueryRequest request = QueryRequest.builder()
        .withSession(state.getSessionId())
        .withSequence(state.getCommandRequest())
        .withIndex(state.getResponseIndex())
        .withQuery(query)
        .build();
      submitQuery(request, future);
    }
  }

  /**
   * Submits a query request to the cluster.
   */
  private <T> void submitQuery(QueryRequest request, CompletableFuture<T> future) {
    QueryAttempt<T> attempt = new QueryAttempt<>(sequencer.nextSequence(), request, future);
    attempts.put(attempt.sequence, attempt);
    submit(attempt);
  }

  /**
   * Submits an operation attempt.
   *
   * @param attempt The attempt to submit.
   */
  private <T extends OperationRequest<T>, U extends OperationResponse<U>, V> void submit(OperationAttempt<T, U, V> attempt) {
    if (!open) {
      attempt.fail(new ClosedSessionException("session closed"));
    } else {
      connection.<T, U>send(attempt.request).whenComplete(attempt);
    }
  }

  /**
   * Closes the submitter.
   *
   * @return A completable future to be completed with a list of pending operations.
   */
  public CompletableFuture<Void> close() {
    CompletableFuture<Void> future = new CompletableFuture<>();
    context.executor().execute(() -> {
      open = false;
    });
    return future;
  }

  /**
   * Operation attempt.
   */
  private abstract class OperationAttempt<T extends OperationRequest<T>, U extends OperationResponse<U>, V> implements RetryStrategy.Attempt, BiConsumer<U, Throwable> {
    protected final long sequence;
    protected final int attempt;
    protected final T request;
    protected final CompletableFuture<V> future;

    protected OperationAttempt(long sequence, int attempt, T request, CompletableFuture<V> future) {
      this.sequence = sequence;
      this.attempt = attempt;
      this.request = request;
      this.future = future;
    }

    @Override
    public int attempt() {
      return attempt;
    }

    @Override
    public Operation<?> operation() {
      return request.operation();
    }

    @Override
    public void accept(U response, Throwable error) {
      if (error == null) {
        if (response.status() == Response.Status.OK) {
          complete(response);
        } else {
          strategy.attemptFailed(this, response.error().createException());
        }
      } else {
        strategy.attemptFailed(this, error);
      }
    }

    /**
     * Returns the next instance of the attempt.
     *
     * @return The next instance of the attempt.
     */
    protected abstract OperationAttempt<T, U, V> next();

    /**
     * Returns a new instance of the default exception for the operation.
     *
     * @return A default exception for the operation.
     */
    protected abstract Throwable defaultException();

    /**
     * Completes the operation successfully.
     *
     * @param response The operation response.
     */
    protected abstract void complete(U response);

    /**
     * Completes the operation with an exception.
     *
     * @param error The completion exception.
     */
    protected abstract void complete(Throwable error);

    /**
     * Runs the given callback in proper sequence.
     *
     * @param callback The callback to run in sequence.
     */
    protected final void sequence(Runnable callback) {
      sequencer.sequence(sequence, callback);
    }

    @Override
    public void fail() {
      fail(defaultException());
    }

    @Override
    public void fail(Throwable t) {
      sequence(() -> complete(t));
    }

    @Override
    public void retry() {
      context.executor().execute(() -> submit(next()));
    }

    @Override
    public void retry(Duration after) {
      retries.put(sequence, context.schedule(after, () -> submit(next())));
    }
  }

  /**
   * Command operation attempt.
   */
  private final class CommandAttempt<T> extends OperationAttempt<CommandRequest, CommandResponse, T> {
    public CommandAttempt(long sequence, CommandRequest request, CompletableFuture<T> future) {
      super(sequence, 1, request, future);
    }

    public CommandAttempt(long sequence, int attempt, CommandRequest request, CompletableFuture<T> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected OperationAttempt<CommandRequest, CommandResponse, T> next() {
      CommandAttempt<T> attempt = new CommandAttempt<>(sequence, this.attempt + 1, request, future);
      attempts.put(attempt.sequence, attempt);
      return attempt;
    }

    @Override
    protected Throwable defaultException() {
      return new CommandException("failed to complete command");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(CommandResponse response) {
      state.setCommandResponse(request.sequence());
      attempts.remove(sequence);
      future.complete((T) response.result());
    }

    @Override
    protected void complete(Throwable error) {
      attempts.remove(sequence);
      future.completeExceptionally(error);
    }
  }

  /**
   * Query operation attempt.
   */
  private final class QueryAttempt<T> extends OperationAttempt<QueryRequest, QueryResponse, T> {
    public QueryAttempt(long sequence, QueryRequest request, CompletableFuture<T> future) {
      super(sequence, 1, request, future);
    }

    public QueryAttempt(long sequence, int attempt, QueryRequest request, CompletableFuture<T> future) {
      super(sequence, attempt, request, future);
    }

    @Override
    protected OperationAttempt<QueryRequest, QueryResponse, T> next() {
      QueryAttempt<T> attempt = new QueryAttempt<>(sequence, this.attempt + 1, request, future);
      attempts.put(attempt.sequence, attempt);
      return attempt;
    }

    @Override
    protected Throwable defaultException() {
      return new QueryException("failed to complete query");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void complete(QueryResponse response) {
      // If the query consistency level is CAUSAL, we can simply complete queries in sequential order.
      if (request.query().consistency() == Query.ConsistencyLevel.CAUSAL) {
        state.setResponseIndex(response.index());
        attempts.remove(sequence);
        future.complete((T) response.result());
      }
      // If the query consistency level is strong, the query must be executed sequentially. In order to ensure responses
      // are received in a sequential manner, we compare the response index number with the highest index for which
      // we've received a response and resubmit queries with output resulting from stale (prior) versions.
      else {
        if (response.index() > 0 && response.index() < state.getResponseIndex()) {
          retry();
        } else {
          state.setResponseIndex(response.index());
          attempts.remove(sequence);
          future.complete((T) response.result());
        }
      }
    }

    @Override
    protected void complete(Throwable error) {
      attempts.remove(sequence);
      future.completeExceptionally(error);
    }
  }

}
