/*
Copyright 2012 Artem Stasuk

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.github.terma.javaniotcpproxy;

import com.github.terma.javaniotcpserver.TcpServerHandler;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

class TcpProxyConnector implements TcpServerHandler, TcpProxyConnectorMBean {

    //
    // TODO: wire up file channel to receive debug output
    // TODO: options to halt the stream immediately, or at a certain point (pattern, or number of bytes?)
    //

    private final static byte[] TLS_CLIENT_HELLO = new byte[] { 0x01, 0x00, 0x00, (byte) 0xb5, 0x03, 0x03 };
    private final static Logger LOGGER = Logger.getAnonymousLogger();

    private final TcpProxyBuffer clientBuffer = new TcpProxyBuffer();
    private final TcpProxyBuffer serverBuffer = new TcpProxyBuffer();
    private final SocketChannel clientChannel;

    private Selector selector;
    private SocketChannel serverChannel;
    private TcpProxyConfig config;
    private AtomicBoolean killed = new AtomicBoolean(false);
    private AtomicBoolean serverHung = new AtomicBoolean(false);

    private ObjectName mbeanName = null;

    public TcpProxyConnector(SocketChannel clientChannel, TcpProxyConfig config) {
        this.clientChannel = clientChannel;
        this.config = config;

        try {
            String objectName = "com.github.terma.javaniotcpproxy:Type=TcpProxyConnector" + "," +
                    "localPort=" + config.getLocalPort() + "," +
                    "remoteHost=" + config.getRemoteHost() + "," +
                    "remotePort=" + config.getRemotePort() + "," +
                    "client=" +  clientChannel.getRemoteAddress().toString().replaceAll(":", "_");

            MBeanServer server = ManagementFactory.getPlatformMBeanServer();

            // Construct the ObjectName for the Hello MBean we will register
            mbeanName = new ObjectName(objectName);

            TcpProxyConnectorMBean mbean = this;
            server.registerMBean(mbean, mbeanName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void readFromClient() throws IOException {
        if (killed.get()) {
            return;
        }
        
        serverBuffer.writeFrom(clientChannel);
        if (serverBuffer.isReadyToRead()) register();
    }

    public void readFromServer() throws IOException {
        if (killed.get()) {
            return;
        }

        clientBuffer.writeFrom(serverChannel);
        if (clientBuffer.isReadyToRead()) register();
    }

    public void writeToClient() throws IOException {
        if (killed.get()) {
            return;
        }

        clientBuffer.writeTo(clientChannel);
        if (clientBuffer.isReadyToWrite()) register();
    }

    public void writeToServer() throws IOException {
        if (killed.get() || serverHung.get()) {
            return;
        }

        serverBuffer.writeTo(serverChannel);

        // This should have the effect of - if the buffer contains a TLS Client Hello packet,
        // we'll write it, but then we'll hang the connection.
        if (! serverBuffer.contains(TLS_CLIENT_HELLO)) {
            serverHung.set(true);
        }
    }

    public void register() throws ClosedChannelException {
        if (killed.get()) {
            return;
        }

        int clientOps = 0;
        if (serverBuffer.isReadyToWrite()) clientOps |= SelectionKey.OP_READ;
        if (clientBuffer.isReadyToRead()) clientOps |= SelectionKey.OP_WRITE;
        clientChannel.register(selector, clientOps, this);

        int serverOps = 0;
        if (clientBuffer.isReadyToWrite()) serverOps |= SelectionKey.OP_READ;
        if (serverBuffer.isReadyToRead()) serverOps |= SelectionKey.OP_WRITE;
        serverChannel.register(selector, serverOps, this);
    }

    private static void closeQuietly(ByteChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException exception) {
                if (LOGGER.isLoggable(Level.WARNING))
                    LOGGER.log(Level.WARNING, "Could not close channel properly.", exception);
            }
        }
    }

    @Override
    public void register(Selector selector) {
        this.selector = selector;

        try {
            clientChannel.configureBlocking(false);

            final InetSocketAddress socketAddress = new InetSocketAddress(
                    config.getRemoteHost(), config.getRemotePort());
            serverChannel = SocketChannel.open();
            serverChannel.connect(socketAddress);
            serverChannel.configureBlocking(false);

            register();
        } catch (final IOException exception) {
            destroy();

            if (LOGGER.isLoggable(Level.WARNING))
                LOGGER.log(Level.WARNING, "Could not connect to "
                        + config.getRemoteHost() + ":" + config.getRemotePort(), exception);
        }
    }

    @Override
    public void process(final SelectionKey key) {

        if (killed.get()) {
            return;
        }

        try {
            if (key.channel() == clientChannel) {
                if (key.isValid() && key.isReadable()) readFromClient();
                if (key.isValid() && key.isWritable()) writeToClient();
            }

            if (key.channel() == serverChannel) {
                if (key.isValid() && key.isReadable()) readFromServer();
                if (key.isValid() && key.isWritable()) writeToServer();
            }
        } catch (final ClosedChannelException exception) {
            destroy();

            if (LOGGER.isLoggable(Level.INFO))
                LOGGER.log(Level.INFO, "Channel was closed by client or server.", exception);
        } catch (final IOException exception) {
            destroy();

            if (LOGGER.isLoggable(Level.WARNING))
                LOGGER.log(Level.WARNING, "Could not process.", exception);
        }
    }

    @Override
    public void destroy() {
        closeQuietly(clientChannel);
        closeQuietly(serverChannel);

        try {
            final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.unregisterMBean(mbeanName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void killServer() {
        killed.set(true);
        closeQuietly(serverChannel);
    }

    @Override
    public void killClient() {
        killed.set(true);
        closeQuietly(clientChannel);
    }

}
