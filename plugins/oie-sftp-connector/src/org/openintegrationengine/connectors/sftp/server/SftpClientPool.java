/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps idle SSH sessions so a destination with several threads, or one sending a steady
 * stream of small messages, is not paying for a handshake per message.
 *
 * <p>Sessions are pooled per {@link SftpClient#signature(SftpConnectionProperties)
 * connection signature}, so a destination whose host or credentials are templated per
 * message simply gets a pool per distinct value rather than a wrong connection.
 */
public class SftpClientPool {

    /**
     * Per signature. A destination cannot use more connections at once than it has
     * threads, so this only bounds what is held while idle -- the cost of getting it wrong
     * is a reconnect, not a failure.
     */
    private static final int MAX_IDLE_PER_SIGNATURE = 8;

    private final Map<String, Deque<SftpClient>> idle = new HashMap<String, Deque<SftpClient>>();
    private boolean closed;

    /**
     * An open client, reused if one is waiting and still alive. A pooled session that has
     * been dropped by the far end is discarded and replaced rather than handed out, which
     * is the whole reason the pool checks liveness on the way out rather than on the way
     * in.
     */
    public SftpClient borrow(SftpConnectionProperties props) throws IOException {
        String signature = SftpClient.signature(props);

        while (true) {
            SftpClient pooled;
            synchronized (this) {
                if (closed) {
                    throw new IOException("Connector is stopping");
                }
                Deque<SftpClient> clients = idle.get(signature);
                pooled = clients == null ? null : clients.pollFirst();
            }
            if (pooled == null) {
                return SftpClient.connect(props);
            }
            if (pooled.isConnected()) {
                return pooled;
            }
            pooled.close();
        }
    }

    /**
     * @param keep false closes the connection instead of pooling it, which is what
     *             {@code keepConnectionOpen} being off means
     */
    public void release(SftpClient client, boolean keep) {
        if (client == null) {
            return;
        }
        if (!keep || !client.isConnected()) {
            client.close();
            return;
        }

        boolean pooled = false;
        synchronized (this) {
            if (!closed) {
                Deque<SftpClient> clients = idle.get(client.getSignature());
                if (clients == null) {
                    clients = new ArrayDeque<SftpClient>();
                    idle.put(client.getSignature(), clients);
                }
                if (clients.size() < MAX_IDLE_PER_SIGNATURE) {
                    clients.addFirst(client);
                    pooled = true;
                }
            }
        }
        if (!pooled) {
            client.close();
        }
    }

    /** Closes everything idle and refuses further borrows. Called when the connector stops. */
    public void close() {
        List<SftpClient> toClose = new ArrayList<SftpClient>();
        synchronized (this) {
            closed = true;
            for (Deque<SftpClient> clients : idle.values()) {
                toClose.addAll(clients);
            }
            idle.clear();
        }
        for (SftpClient client : toClose) {
            client.close();
        }
    }

    /** Reopens the pool after a {@link #close()}, for a connector that is starting again. */
    public synchronized void reset() {
        closed = false;
    }
}
