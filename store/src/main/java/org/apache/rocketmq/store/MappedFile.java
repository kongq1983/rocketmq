/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.util.LibC;
import sun.nio.ch.DirectBuffer;

public class MappedFile extends ReferenceResource {
    public static final int OS_PAGE_SIZE = 1024 * 4; //常量，内存页大小，4KB
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);

    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);  // flushedPosition(磁盘位置) <= committedPosition(pagecache位置) <= wrotePosition(内存位置)
    protected final AtomicInteger wrotePosition = new AtomicInteger(0); //上次写的位置-appendMessage处理  fileChannel.write
    protected final AtomicInteger committedPosition = new AtomicInteger(0); //已经提交的位置(上一次写的位置)
    private final AtomicInteger flushedPosition = new AtomicInteger(0); //已经刷盘的位置  fileChannel.force
    protected int fileSize; //文件大小
    protected FileChannel fileChannel; //该MappedFile文件对应的channel
    /** 如果启用了TransientStorePool，则writeBuffer为从暂时存储池中借用 的buffer，此时存储对象（比如消息等）会先写入该writeBuffer，然后commit到fileChannel，最后对fileChannel进行flush刷盘
     * Message will put to here first, and then reput to FileChannel if writeBuffer is not null.
     */
    protected ByteBuffer writeBuffer = null; // this.writeBuffer = transientStorePool.borrowBuffer();   通道写入的是这个writeBuffer，this.fileChannel.write(byteBuffer)
    protected TransientStorePool transientStorePool = null; //一个内存ByteBuffer池实现，如果启用了TransientStorePool则不为空
    private String fileName; //文件名，其实就是该文件内容默认其实位置
    private long fileFromOffset; //该文件中内容相对于整个文件的偏移，其实和文件名相同  当前文件名
    private File file; //该MappedFile对应的实际文件
    private MappedByteBuffer mappedByteBuffer; //通过fileChannel.map得到的可读写的内存映射buffer，如果没有启用TransientStorePool则写数据时会写到该缓冲中，刷盘时直接调用该映射buffer的force函数，而不需要进行commit操作
    private volatile long storeTimestamp = 0;
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }
    /** 调用init的时候 会创建文件 如果已存在 会映射 */
    public MappedFile(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }

    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    public static void clean(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Method method = method(target, methodName, args);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";
        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                methodName = "attachment";
                break;
            }
        }

        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    public void init(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize);
        this.writeBuffer = transientStorePool.borrowBuffer();
        this.transientStorePool = transientStorePool;
    }

    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName; // 文件名称
        this.fileSize = fileSize; // 文件大小 commitlog默认:1024*1024*1024
        this.file = new File(fileName);
        this.fileFromOffset = Long.parseLong(this.file.getName()); //从文件名提取起始offset
        boolean ok = false;

        ensureDirOK(this.file.getParent()); // 确保父目录存在

        try {
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize); //mmap  内存映射
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize); // 总的虚拟内存映射大小
            TOTAL_MAPPED_FILES.incrementAndGet(); // 映射的文件有几个
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("Failed to create file " + this.fileName, e);
            throw e;
        } catch (IOException e) {
            log.error("Failed to map file " + this.fileName, e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    public long getLastModifiedTimestamp() {
        return this.file.lastModified();
    }

    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        return appendMessagesInner(msg, cb);
    }

    public AppendMessageResult appendMessages(final MessageExtBatch messageExtBatch, final AppendMessageCallback cb) {
        return appendMessagesInner(messageExtBatch, cb);
    }
    /** 写消息 */
    public AppendMessageResult appendMessagesInner(final MessageExt messageExt, final AppendMessageCallback cb) {
        assert messageExt != null;
        assert cb != null;
        //上次写的位置
        int currentPos = this.wrotePosition.get();
        // 小于当前文件长度 当前文件可以写入本次数据      下面this.writeBuffer = transientStorePool.borrowBuffer();
        if (currentPos < this.fileSize) { //仅当transientStorePoolEnable 为true，刷盘策略为异步刷盘（FlushDiskType为ASYNC_FLUSH）,并且broker为主节点时，才启用堆外分配内存。此时：writeBuffer不为null
            ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice(); //Buffer与同步和异步刷盘相关 //writeBuffer/mappedByteBuffer的position始终为0，而limit则始终等于capacity
            byteBuffer.position(currentPos); //设置写的起始位置         //slice创建一个新的buffer, 是根据position和limit来生成byteBuffer
            AppendMessageResult result;
            if (messageExt instanceof MessageExtBrokerInner) { //处理单个消息   fileSize - currentPos = 当前文件剩余可用  重要:单个消息进入这里 **********
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt);
            } else if (messageExt instanceof MessageExtBatch) { //处理批量消息
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBatch) messageExt); // doAppend会把消息写到byteBuffer
            } else {
                return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
            }
            this.wrotePosition.addAndGet(result.getWroteBytes());  //修改写的位置
            this.storeTimestamp = result.getStoreTimestamp();
            return result;
        }
        log.error("MappedFile.appendMessage return null, wrotePosition: {} fileSize: {}", currentPos, this.fileSize);
        return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR); //写满会报错，正常不会进入该代码，调用该方法前有判断
    }

    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) { // 判断当前文件剩余空间是否足够
            try {
                this.fileChannel.position(currentPos); // 跳转到指定位置  当前写的位置
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length); // 设置新的写的位置
            return true;
        }

        return false;
    }

    /**
     * Content of data from offset to offset + length will be wrote to file.
     *
     * @param offset The offset of the subarray to be used.
     * @param length The length of the subarray to be used.
     */
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data, offset, length));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(length);
            return true;
        }

        return false;
    }

    /** 写磁盘
     * @return The current flushed position
     */
    public int flush(final int flushLeastPages) {
        if (this.isAbleToFlush(flushLeastPages)) { // 第1种情况，强制刷盘，参数为0，有数据就会刷盘  第2种情况，参数为页数（2），达到指定大小才会刷盘   第3种情况，数据当前文件写满了，就刷盘
            if (this.hold()) {
                int value = getReadPosition();

                try {// 把数据写到fileChannel或者mappedByteBuffer，不会同时写到2个地方
                    //We only append data to fileChannel or mappedByteBuffer, never both.
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                this.flushedPosition.set(value); // 新的刷盘位置
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition()); // wrotePosition的位置
            }
        }
        return this.getFlushedPosition();
    }
    /** 提交消息到FileChannel */
    public int commit(final int commitLeastPages) {
        if (writeBuffer == null) { // commitLeastPages 为本次提交最小的页数，如果待提交数据不满commitLeastPages(默认4*4kb)，则不执行本次提交操作，待下次提交
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        if (this.isAbleToCommit(commitLeastPages)) {
            if (this.hold()) {
                commit0(commitLeastPages);
                this.release(); // 清理工作
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // All dirty data has been committed to FileChannel.
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer); // availableBuffers.offerFirst 从池中删除writeBuffer
            this.writeBuffer = null; // gc
        }

        return this.committedPosition.get();
    }
    /** 同步 */
    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        if (writePos - lastCommittedPosition > commitLeastPages) {
            try {
                ByteBuffer byteBuffer = writeBuffer.slice();
                byteBuffer.position(lastCommittedPosition);
                byteBuffer.limit(writePos);
                this.fileChannel.position(lastCommittedPosition); // 移动到最后位置
                this.fileChannel.write(byteBuffer); // 添加新的数据
                this.committedPosition.set(writePos);  // 除了服务器重启，其他地方 就这地方设置committedPosition
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }
    // 第1种情况，强制刷盘，参数为0，有数据就会刷盘  第2种情况，参数为页数（2），达到指定大小才会刷盘   第3种情况，数据当前文件写满了，就刷盘
    private boolean isAbleToFlush(final int flushLeastPages) {
        int flush = this.flushedPosition.get();
        int write = getReadPosition();

        if (this.isFull()) {  // 当前文件写满了，返回true，可以刷盘
            return true;
        }
        // flushLeastPages是在FlushRealTimeService的run方法中设置的
        if (flushLeastPages > 0) { // OS_PAGE_SIZE = 1024 * 4 = 4K = 每页4K
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= flushLeastPages;  // 是否达到指定页数，达到了返回true，没达到返回false
        }
        // 强制刷盘(flushLeastPages==0)，只要写的位置比上次刷盘的位置大就行了
        return write > flush;
    }
    /** commitLeastPages 为本次提交最小的页数，如果待提交数据不满commitLeastPages(默认4*4kb)，则不执行本次提交操作，待下次提交 */
    protected boolean isAbleToCommit(final int commitLeastPages) {
        int flush = this.committedPosition.get();
        int write = this.wrotePosition.get();

        if (this.isFull()) { // 文件已写满
            return true;
        }

        if (commitLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= commitLeastPages; // 最少达到commitLeastPages页才提交
        }
        // commitLeastPages = 0
        return write > flush; // commitLeastPages = 0情况下，有数据写入  就提交
    }

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }
    /** pos = 当前文件中的开始位置 可读的数据封装成ByteBuffer 最后返回 SelectMappedBufferResult */
    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition(); // 写的偏移量 this.wrotePosition.get() : this.committedPosition.get()
        if (pos < readPosition && pos >= 0) { // 当所需要查找的偏移量小于写偏移量才有数据，截取从偏移量开始的ByteBuffer
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                int size = readPosition - pos;
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    @Override
    public boolean cleanup(final long currentRef) {
        if (this.isAvailable()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmapping.");
            return false;
        }

        if (this.isCleanupOver()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        clean(this.mappedByteBuffer);
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        TOTAL_MAPPED_FILES.decrementAndGet();
        log.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    public boolean destroy(final long intervalForcibly) {
        this.shutdown(intervalForcibly);

        if (this.isCleanupOver()) {
            try {
                this.fileChannel.close();
                log.info("close file channel " + this.fileName + " OK");

                long beginTime = System.currentTimeMillis();
                boolean result = this.file.delete();
                log.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + UtilAll.computeElapsedTimeMilliseconds(beginTime));
            } catch (Exception e) {
                log.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            log.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * @return The max position which have valid data
     */
    public int getReadPosition() {
        return this.writeBuffer == null ? this.wrotePosition.get() : this.committedPosition.get();
    }

    public void setCommittedPosition(int pos) { // 服务器重启的时候 调用
        this.committedPosition.set(pos);
    }
    // todo 文件预热  PUT数据的时候  这里面会预先写数据
    public void warmMappedFile(FlushDiskType type, int pages) { // pages = 16
        long beginTime = System.currentTimeMillis();
        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        int flush = 0;
        long time = System.currentTimeMillis(); // OS_PAGE_SIZE = 1024 * = 4K  IO操作性能，另外一点就是mmap后的虚拟内存大小必须是内存页大小(通常是4K)的倍数
        for (int i = 0, j = 0; i < this.fileSize; i += MappedFile.OS_PAGE_SIZE, j++) { // 第1次  i=0  第2次 i=4K  第3次 i=8K
            byteBuffer.put(i, (byte) 0); //在一个4K的pagecache中，起始position写入一个字节
            // force flush when flush disk type is sync
            if (type == FlushDiskType.SYNC_FLUSH) { // 在一个4K的pagecache中，起始position写入一个字节
                if ((i / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE) >= pages) { // 第一次:0   第2次:(4K/OS_PAGE_SIZE) =1  第3次:(8K/OS_PAGE_SIZE)=2
                    flush = i;  // 每写完一个pagecache  就flush   进入这里 相当于 i=64K   相当于每64K刷一次
                    mappedByteBuffer.force();
                }
            }

            // prevent gc
            if (j % 1000 == 0) {
                log.info("j={}, costTime={}", j, System.currentTimeMillis() - time);
                time = System.currentTimeMillis();
                try {
                    Thread.sleep(0); // sleep(0)=yield()  让其他线程有机会运行  prevent gc
                } catch (InterruptedException e) {
                    log.error("Interrupted", e);
                }
            }
        }

        // force flush when prepare load finished
        if (type == FlushDiskType.SYNC_FLUSH) {
            log.info("mapped file warm-up done, force to disk, mappedFile={}, costTime={}",
                this.getFileName(), System.currentTimeMillis() - beginTime);
            mappedByteBuffer.force();
        }
        log.info("mapped file warm-up done. mappedFile={}, costTime={}", this.getFileName(),
            System.currentTimeMillis() - beginTime);

        this.mlock(); // 关注这个  锁住内存是为了防止这段内存被操作系统swap
    }

    public String getFileName() {
        return fileName;
    }

    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }
    // todo mlock
    public void mlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address(); // 开始地址
        Pointer pointer = new Pointer(address); // 锁住内存是为了防止这段内存被操作系统swap掉。并且由于此操作风险高，仅超级用户可以执行
        { // 可以将进程使用的部分或者全部的地址空间锁定在物理内存中，防止其被交换到swap空间
            int ret = LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize)); // 长度
            log.info("mlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        } // Linux 分配内存到页(page)且每次只能锁定整页内存，被指定的区间涉及到的每个内存页都将被锁定

        { // 给操作系统建议，说这文件在不久的将来要访问的，因此，提前读几页可能是个好主意
            int ret = LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
            log.info("madvise {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }
    }
    // todo munlock
    public void munlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address(); // 开始地址
        Pointer pointer = new Pointer(address);
        int ret = LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize)); // munlock 系统调用会将当前进程锁定的所有内存解锁，包括经由 mlock 或 mlockall 锁定的所有区间
        log.info("munlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
    }

    //testable
    File getFile() {
        return this.file;
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}
