package com.github.terma.javaniotcpproxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;

public class TeeByteChannel implements ByteChannel {

    //
    // TODO: translate byte buffer to hex
    // TODO: add directional arrows and horizontal rules so we can see the flow
    //


    private final ByteChannel delegate;
    private final FileChannel fileChannel;

    public TeeByteChannel(final ByteChannel delegate, final FileChannel fileChannel) {
        this.delegate = delegate;
        this.fileChannel = fileChannel;
    }

    @Override
    public int read(final ByteBuffer byteBuffer) throws IOException {
        final int read = delegate.read(byteBuffer);

        final ByteBuffer duplicate = byteBuffer.duplicate();
        duplicate.flip();
        fileChannel.write(duplicate);

        return read;
    }

    @Override
    public int write(final ByteBuffer byteBuffer) throws IOException {
        final ByteBuffer duplicate = byteBuffer.duplicate();
        fileChannel.write(duplicate);

        return delegate.write(byteBuffer);
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    public Channel getDelegateChannel() {
        return delegate;
    }

    public final SelectionKey register(final Selector sel, final int ops, final Object att) throws ClosedChannelException {
        if (! SelectableChannel.class.isInstance(delegate)) {
            throw new IllegalStateException("Channel is not a SelectableChannel");
        }

        return SelectableChannel.class.cast(delegate).register(sel, ops, att);
    }

    public void configureBlocking(final boolean blocking) throws IOException {
        if (! SelectableChannel.class.isInstance(delegate)) {
            throw new IllegalStateException("Channel is not a SelectableChannel");
        }

        SelectableChannel.class.cast(delegate).configureBlocking(blocking);
    }
}
