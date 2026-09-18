/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.server.util.SqlConfig;

import org.apache.ibatis.session.SqlSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queued work belonging to an engine that is not here any more.
 *
 * <p>Donkey scopes a queue by {@code SERVER_ID}: {@code getConnectorMessagesByMetaDataIdAndStatus}
 * and the recovery queries all filter on it, which is exactly why two engines can share a
 * channel's tables safely. The other side of that is that when a pod is scaled away for
 * good, the messages it had queued belong to an id nothing answers to, and no other node
 * will ever pick them up.
 *
 * <p>So this class counts them, and can move or neutralise them -- and does none of it on
 * its own initiative. Reassigning a message to another server writes into message history
 * and cannot be undone, which makes it a decision for the person looking at the specific
 * channels and counts, not a policy compiled into a convergence loop. The API names every
 * affected channel individually and the console defaults to the answer that changes
 * nothing.
 *
 * <h2>Why raw SQL here and nowhere else</h2>
 *
 * <p>The rest of the plugin goes through the engine's controllers. There is no controller
 * for this: the per-channel {@code D_M} / {@code D_MM} tables are Donkey's own, addressed
 * by a local channel id from {@code D_CHANNELS}, and nothing above them exposes "whose is
 * this". The ids interpolated into the table names are read back as {@code long}s from
 * {@code D_CHANNELS} in the same query, so they cannot carry anything but a number.
 */
public final class OrphanStore {

    private static final Logger LOG = LogManager.getLogger(OrphanStore.class);

    /** What one departed server id still holds on one channel. */
    public static final class Orphan {
        final String serverId;
        final String channelId;
        final String channelName;
        final long queued;
        final long unprocessed;

        Orphan(String serverId, String channelId, String channelName,
               long queued, long unprocessed) {
            this.serverId = serverId;
            this.channelId = channelId;
            this.channelName = channelName;
            this.queued = queued;
            this.unprocessed = unprocessed;
        }

        public String getServerId() {
            return serverId;
        }

        public String getChannelId() {
            return channelId;
        }

        public String getChannelName() {
            return channelName;
        }

        public long getQueued() {
            return queued;
        }

        public long getUnprocessed() {
            return unprocessed;
        }
    }

    private OrphanStore() {
    }

    private static SqlSession session() {
        // autoCommit: these are single statements with no wider transaction to join.
        return SqlConfig.getInstance().getSqlSessionManager().openSession(true);
    }

    /** channelId to local channel id, from Donkey's own map. */
    private static Map<String, Long> localChannelIds(Connection connection) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT CHANNEL_ID, LOCAL_CHANNEL_ID FROM D_CHANNELS")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /**
     * Every server id with queued or unfinished work, excluding the ones given as live.
     *
     * <p>Scanned from the message tables rather than from the node registry, so work left
     * by an engine that was retired before this extension was ever installed is still
     * found. Restricted to queued and unprocessed rows, which are both small and both
     * indexed; a full scan of message history would be neither.
     */
    public static List<Orphan> scan(Set<String> liveServerIds, Map<String, String> channelNames) {
        List<Orphan> out = new ArrayList<>();
        // MyBatis 3.1.1 -- the version the engine ships -- predates SqlSession being
        // AutoCloseable, so the close is explicit rather than a try-with-resources.
        SqlSession session = session();
        try {
            Connection connection = session.getConnection();
            for (Map.Entry<String, Long> entry : localChannelIds(connection).entrySet()) {
                String channelId = entry.getKey();
                long localId = entry.getValue();
                Map<String, long[]> perServer = new LinkedHashMap<>();

                count(connection,
                    "SELECT SERVER_ID, COUNT(*) FROM D_MM" + localId
                        + " WHERE STATUS = 'Q' GROUP BY SERVER_ID",
                    perServer, 0);
                count(connection,
                    "SELECT SERVER_ID, COUNT(*) FROM D_M" + localId
                        + " WHERE PROCESSED = FALSE GROUP BY SERVER_ID",
                    perServer, 1);

                for (Map.Entry<String, long[]> e : perServer.entrySet()) {
                    if (e.getKey() == null || liveServerIds.contains(e.getKey())) {
                        continue;
                    }
                    long[] counts = e.getValue();
                    if (counts[0] == 0 && counts[1] == 0) {
                        continue;
                    }
                    out.add(new Orphan(e.getKey(), channelId,
                        channelNames.getOrDefault(channelId, channelId),
                        counts[0], counts[1]));
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOG.error("cluster: could not scan for orphaned queues", e);
        } finally {
            session.close();
        }
        return out;
    }

    private static void count(Connection connection, String sql,
                              Map<String, long[]> into, int slot) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String serverId = rs.getString(1);
                into.computeIfAbsent(serverId, k -> new long[2])[slot] = rs.getLong(2);
            }
        } catch (SQLException e) {
            // A channel deployed but never used has its tables; one mid-removal may not.
            // Either way, one channel's tables being unreadable is not a reason to report
            // nothing about the others.
            LOG.debug("cluster: orphan count skipped: {}", e.getMessage());
        }
    }

    /**
     * Hands one departed server's messages on a channel to a live node.
     *
     * <p>Both tables, together: {@code D_M} carries ownership of the message and
     * {@code D_MM} of each connector's attempt at it, and Donkey's recovery reads them as a
     * pair. Moving one without the other produces a message the new owner can see and not
     * finish.
     *
     * <p>The receiving node picks the messages up when the channel next starts its queue,
     * which is why the caller is told to redeploy the channel afterwards rather than left
     * to wonder why nothing moved.
     */
    public static long adopt(String channelId, String fromServerId, String toServerId) {
        return update(channelId, (connection, localId) -> {
            long moved = 0;
            moved += exec(connection,
                "UPDATE D_M" + localId + " SET SERVER_ID = ? WHERE SERVER_ID = ?",
                toServerId, fromServerId);
            exec(connection,
                "UPDATE D_MM" + localId + " SET SERVER_ID = ? WHERE SERVER_ID = ?",
                toServerId, fromServerId);
            return moved;
        });
    }

    /**
     * Stops one departed server's queued messages from being anybody's pending work,
     * without deleting anything.
     *
     * <p>The queued connector messages become errors and the messages become processed, so
     * they leave the queue and stop being counted as outstanding. Every message, its
     * content and its history stay exactly where they were and remain visible in the
     * message browser, which is the difference between this and a delete -- and the reason
     * the console can offer it as the middle answer rather than the drastic one.
     */
    public static long discard(String channelId, String fromServerId) {
        return update(channelId, (connection, localId) -> {
            long affected = exec(connection,
                "UPDATE D_MM" + localId + " SET STATUS = 'E' "
                    + "WHERE SERVER_ID = ? AND STATUS = 'Q'", fromServerId);
            exec(connection,
                "UPDATE D_M" + localId + " SET PROCESSED = TRUE "
                    + "WHERE SERVER_ID = ? AND PROCESSED = FALSE", fromServerId);
            return affected;
        });
    }

    private interface Work {
        long run(Connection connection, long localChannelId) throws SQLException;
    }

    private static long update(String channelId, Work work) {
        SqlSession session = session();
        try {
            Connection connection = session.getConnection();
            Long localId = localChannelIds(connection).get(channelId);
            if (localId == null) {
                throw new IllegalArgumentException("unknown channel " + channelId);
            }
            return work.run(connection, localId);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            session.close();
        }
    }

    private static long exec(Connection connection, String sql, String... params)
        throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
