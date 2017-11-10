package lv.sergluka.tws;

import com.ib.client.*;
import lv.sergluka.tws.impl.ConnectionMonitor;
import lv.sergluka.tws.impl.TwsBaseWrapper;
import lv.sergluka.tws.impl.TwsReader;
import lv.sergluka.tws.impl.TwsSender;
import lv.sergluka.tws.impl.future.TwsFuture;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class TwsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TwsClient.class);
    private static final int INVALID_ID = -1;
    private EClientSocket socket;
    private TwsReader reader;
    private TwsSender sender;
    private ConnectionMonitor connectionMonitor;
    private TwsBaseWrapper wrapper;
    private AtomicInteger requestId;

    @Override
    public void close() throws Exception {
        connectionMonitor.disconnect();
        connectionMonitor.close();
    }

    public void connect(final @NotNull String ip, final int port, final int connId) {
        log.debug("Connecting...");

        if (isConnected()) {
            log.warn("Already is connected");
            return;
        }

        requestId = new AtomicInteger(INVALID_ID);

        EJavaSignal signal = new EJavaSignal();
        sender = new TwsSender(this);
        wrapper = new TwsWrapper(sender);
        socket = new EClientSocket(wrapper, signal);
        reader = new TwsReader(socket, signal);
        connectionMonitor = new ConnectionMonitor(socket, reader);
        connectionMonitor.start();

        TwsFuture fConnect = sender.postInternalRequest(TwsSender.Event.REQ_CONNECT,
                () -> connectionMonitor.connect(ip, port, connId));
        fConnect.get();
    }

    public void disconnect() throws TimeoutException {
        log.debug("Disconnecting...");
        connectionMonitor.disconnect();
        log.info("Disconnected");
    }

    public boolean isConnected() {
        return socket != null &&
                socket.isConnected() &&
                connectionMonitor.status() == ConnectionMonitor.Status.CONNECTED;
    }

    @NotNull
    public TwsFuture<Integer> reqCurrentTime() {
        return sender.postSingleRequest(TwsSender.Event.REQ_CURRENT_TIME, null, () -> socket.reqCurrentTime());
    }

    @NotNull
    public TwsFuture<Object> placeOrder(@NotNull Contract contract, @NotNull Order order) {
        final Integer id = getAndRefreshRequestId();
        return sender.postSingleRequest(TwsSender.Event.REQ_ORDER_PLACE, id,
                () -> socket.placeOrder(id, contract, order));
    }

    @NotNull
    public TwsFuture<List<ContractDetails>> reqContractDetails(@NotNull Contract contract) {
        shouldBeConnected();

        final Integer id = getAndRefreshRequestId();
        return sender.postListRequest(TwsSender.Event.REQ_CONTRACT_DETAIL, id,
                () -> socket.reqContractDetails(id, contract));
    }

    private int getAndRefreshRequestId() {
        if (requestId.get() == INVALID_ID) {
            throw new IllegalStateException("Has no request ID from TWS");
        }

        int id = requestId.get();
        socket.reqIds(-1);
        return id;
    }

    private void shouldBeConnected() {
        if (!isConnected()) {
            throw new TwsExceptions.NotConnected();
        }
    }

    private class TwsWrapper extends TwsBaseWrapper {

        TwsWrapper(TwsSender sender) {
            super(sender);
        }

        @Override
        public void connectAck() {
            log.info("Connection is opened. version: {}", socket.serverVersion());
            reader.start(); // TODO: maybe move it into Conn Manager thread

            connectionMonitor.confirmConnection();
            super.connectAck();

        }

        @Override
        public void connectionClosed() {
            log.error("TWS closes the connection");

            connectionMonitor.reconnect();
        }

        @Override
        public void error(final Exception e) {
            if (e instanceof SocketException) {

                final ConnectionMonitor.Status connectionStatus = connectionMonitor.status();

                if (connectionStatus == ConnectionMonitor.Status.DISCONNECTING ||
                        connectionStatus == ConnectionMonitor.Status.DISCONNECTED) {

                    log.debug("Socket has been closed at shutdown");
                    return;
                }

                log.warn("Connection lost", e);
                connectionMonitor.reconnect();
            }

            log.error("TWS error", e);
        }

        @Override
        public void error(final String str) {
            log.error("TWS error: ", str);
        }

        @Override
        public void error(final int id, final int errorCode, final String errorMsg) {

            if (id == -1 && (errorCode == 2104 || errorCode == 2106)) {
                log.debug("Server message - {}", errorMsg);
                return;
            }

            log.error("Terminal returns an error: id={}, code={}, msg={}", id, errorCode, errorMsg);

            switch (errorCode) {
                case 503: // The TWS is out of date and must be upgraded
                    log.error("Incompatible cline/server versions. Shutdown.");
                    connectionMonitor.disconnect();
                    break;
                default:
                    log.error("Try to reconnect on error");
                    connectionMonitor.reconnect();
                    break;
            }

            if (id >= 0) {
                sender.setError(id, new TwsExceptions.ServerError(errorMsg, errorCode));
            }
        }

        @Override
        public void nextValidId(int id) {
            log.debug("New request ID: {}", id);
            requestId.set(id);
        }
    }
}
