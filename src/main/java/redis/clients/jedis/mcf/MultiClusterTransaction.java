package redis.clients.jedis.mcf;

import static redis.clients.jedis.Protocol.Command.DISCARD;
import static redis.clients.jedis.Protocol.Command.EXEC;
import static redis.clients.jedis.Protocol.Command.MULTI;
import static redis.clients.jedis.Protocol.Command.UNWATCH;
import static redis.clients.jedis.Protocol.Command.WATCH;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.graph.ResultSet;
import redis.clients.jedis.providers.MultiClusterPooledConnectionProvider;
import redis.clients.jedis.util.KeyValue;

/**
 * This is high memory dependent solution as all the appending commands will be hold in memory.
 */
public class MultiClusterTransaction extends TransactionBase {

  private static final Builder<?> NO_OP_BUILDER = BuilderFactory.RAW_OBJECT;

  private final CircuitBreakerFailoverConnectionProvider provider;
  private final AtomicInteger extraCommandCount = new AtomicInteger();
  private final Queue<KeyValue<CommandArguments, Response<?>>> commands = new LinkedList<>();

  private boolean inWatch = false;
  private boolean inMulti = false;

  /**
   * A MULTI command will be added to be sent to server. WATCH/UNWATCH/MULTI commands must not be
   * called with this object.
   * @param provider
   */
  public MultiClusterTransaction(MultiClusterPooledConnectionProvider provider) {
    this(provider, true);
  }

  /**
   * A user wanting to WATCH/UNWATCH keys followed by a call to MULTI ({@link #multi()}) it should
   * be {@code doMulti=false}.
   *
   * @param provider
   * @param doMulti {@code false} should be set to enable manual WATCH, UNWATCH and MULTI
   */
  public MultiClusterTransaction(MultiClusterPooledConnectionProvider provider, boolean doMulti) {
    try (Connection connection = provider.getConnection()) { // we don't need a healthy connection now
      RedisProtocol proto = connection.getRedisProtocol();
      if (proto != null) this.commandObjects.setProtocol(proto);
    }

    this.provider = new CircuitBreakerFailoverConnectionProvider(provider);

    if (doMulti) multi();
  }

  @Override
  public final void multi() {
    appendCommand(new CommandObject<>(new CommandArguments(MULTI), NO_OP_BUILDER));
    extraCommandCount.incrementAndGet();
    inMulti = true;
  }

  /**
   * @param keys
   * @return {@code null}
   */
  @Override
  public final String watch(String... keys) {
    appendCommand(new CommandObject<>(new CommandArguments(WATCH).addObjects((Object[]) keys), NO_OP_BUILDER));
    extraCommandCount.incrementAndGet();
    inWatch = true;
    return null;
  }

  /**
   * @param keys
   * @return {@code null}
   */
  @Override
  public final String watch(byte[]... keys) {
    appendCommand(new CommandObject<>(new CommandArguments(WATCH).addObjects((Object[]) keys), NO_OP_BUILDER));
    extraCommandCount.incrementAndGet();
    inWatch = true;
    return null;
  }

  /**
   * @return {@code null}
   */
  @Override
  public final String unwatch() {
    appendCommand(new CommandObject<>(new CommandArguments(UNWATCH), NO_OP_BUILDER));
    extraCommandCount.incrementAndGet();
    inWatch = false;
    return null;
  }

  @Override
  protected final <T> Response<T> appendCommand(CommandObject<T> commandObject) {
    CommandArguments args = commandObject.getArguments();
    Response<T> response = new Response<>(commandObject.getBuilder());
    commands.add(KeyValue.of(args, response));
    return response;
  }

  @Override
  public void close() {
    clear();
  }

  private void clear() {
    if (inMulti) {
      discard();
    } else if (inWatch) {
      unwatch();
    }
  }

  @Override
  public final List<Object> exec() {
    if (!inMulti) {
      throw new IllegalStateException("EXEC without MULTI");
    }

    try (Connection connection = provider.getConnection()) {

      commands.forEach((command) -> connection.sendCommand(command.getKey()));
      // following connection.getMany(int) flushes anyway, so no flush here.

      // ignore QUEUED (or ERROR)
      connection.getMany(commands.size());

      // remove extra response builders
      for (int idx = 0; idx < extraCommandCount.get(); ++idx) {
        commands.poll();
      }

      connection.sendCommand(EXEC);

      List<Object> unformatted = connection.getObjectMultiBulkReply();
      if (unformatted == null) {
        commands.clear();
        return null;
      }

      List<Object> formatted = new ArrayList<>(unformatted.size() - extraCommandCount.get());
      for (Object rawReply: unformatted) {
        try {
          Response<?> response = commands.poll().getValue();
          response.set(rawReply);
          formatted.add(response.get());
        } catch (JedisDataException e) {
          formatted.add(e);
        }
      }
      return formatted;

    } finally {
      inMulti = false;
      inWatch = false;
    }
  }

  @Override
  public final String discard() {
    if (!inMulti) {
      throw new IllegalStateException("DISCARD without MULTI");
    }

    try (Connection connection = provider.getConnection()) {

      commands.forEach((command) -> connection.sendCommand(command.getKey()));
      // following connection.getMany(int) flushes anyway, so no flush here.

      // ignore QUEUED (or ERROR)
      connection.getMany(commands.size());

      connection.sendCommand(DISCARD);

      return connection.getStatusCodeReply();
    } finally {
      inMulti = false;
      inWatch = false;
    }
  }

  // RedisGraph commands
  @Override
  public Response<ResultSet> graphQuery(String name, String query) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphReadonlyQuery(String name, String query) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphQuery(String name, String query, long timeout) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphReadonlyQuery(String name, String query, long timeout) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphQuery(String name, String query, Map<String, Object> params) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphReadonlyQuery(String name, String query, Map<String, Object> params) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphQuery(String name, String query, Map<String, Object> params, long timeout) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<ResultSet> graphReadonlyQuery(String name, String query, Map<String, Object> params, long timeout) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<String> graphDelete(String name) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }

  @Override
  public Response<List<String>> graphProfile(String graphName, String query) {
    throw new UnsupportedOperationException("Graph commands are not supported.");
  }
  // RedisGraph commands
}
