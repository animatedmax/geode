/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.tier.sockets;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;

import org.apache.geode.SerializationException;
import org.apache.geode.annotations.Immutable;
import org.apache.geode.annotations.internal.MakeNotStatic;
import org.apache.geode.internal.Assert;
import org.apache.geode.internal.HeapDataOutputStream;
import org.apache.geode.internal.cache.TXManagerImpl;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.offheap.StoredObject;
import org.apache.geode.internal.offheap.annotations.Unretained;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.util.BlobHelper;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.util.internal.GeodeGlossary;

/**
 * This class encapsulates the wire protocol. It provides accessors to encode and decode a message
 * and serialize it out to the wire.
 *
 * <PRE>
 * messageType       - int   - 4 bytes type of message, types enumerated below
 *
 * msgLength     - int - 4 bytes   total length of variable length payload
 *
 * numberOfParts - int - 4 bytes   number of elements (LEN-BYTE* pairs)
 *                     contained in the payload. Message can
 *                       be a multi-part message
 *
 * transId       - int - 4 bytes  filled in by the requester, copied back into
 *                    the response
 *
 * flags         - byte- 1 byte   filled in by the requester
 * len1
 * part1
 * .
 * .
 * .
 * lenn
 * partn
 * </PRE>
 *
 * We read the fixed length 16 bytes into a byte[] and populate a bytebuffer We read the fixed
 * length header tokens from the header parse the header and use information contained in there to
 * read the payload.
 *
 * <P>
 *
 * See also <a href="package-summary.html#messages">package description</a>.
 *
 * @see MessageType
 */
public class Message {

  // Tentative workaround to avoid OOM stated in #46754.
  public static final ThreadLocal<Integer> MESSAGE_TYPE = new ThreadLocal<>();

  public static final String MAX_MESSAGE_SIZE_PROPERTY =
      GeodeGlossary.GEMFIRE_PREFIX + "client.max-message-size";

  static final int DEFAULT_MAX_MESSAGE_SIZE = 1073741824;

  private static final Logger logger = LogService.getLogger();

  private static final int PART_HEADER_SIZE = 5; // 4 bytes for length, 1 byte for isObject

  private static final int FIXED_LENGTH = 17;

  private static final ThreadLocal<ByteBuffer> tlCommBuffer = new ThreadLocal<>();

  // These two statics are fields shoved into the flags byte for transmission.
  // The MESSAGE_IS_RETRY bit is stripped out during deserialization but the other
  // is left in place
  private static final byte MESSAGE_HAS_SECURE_PART = (byte) 0x02;
  private static final byte MESSAGE_IS_RETRY = (byte) 0x04;

  private static final byte MESSAGE_IS_RETRY_MASK = (byte) 0xFB;

  private static final int DEFAULT_CHUNK_SIZE = 1024;

  @Immutable
  private static final byte[] TRUE = defineTrue();
  @Immutable
  private static final byte[] FALSE = defineFalse();

  private static byte[] defineTrue() {
    try (HeapDataOutputStream hdos = new HeapDataOutputStream(10, null)) {
      BlobHelper.serializeTo(Boolean.TRUE, hdos);
      return hdos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] defineFalse() {
    try (HeapDataOutputStream hdos = new HeapDataOutputStream(10, null)) {
      BlobHelper.serializeTo(Boolean.FALSE, hdos);
      return hdos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The maximum size of an outgoing message. If the message is larger than this maximum, it may
   * cause the receiver to throw an exception on message part length mismatch due to overflow in
   * message size.
   *
   * This value is STATIC because getting a system property requires holding a lock. It is costly to
   * do this for every message sent. If this value needs to be modified for testing, please add a
   * new constructor.
   */
  private static final int maxMessageSize =
      Integer.getInteger(MAX_MESSAGE_SIZE_PROPERTY, DEFAULT_MAX_MESSAGE_SIZE);

  protected int messageType;
  private int payloadLength = 0;
  int numberOfParts = 0;
  protected int transactionId = TXManagerImpl.NOTX;
  int currentPart = 0;
  private Part[] partsList = null;
  private ByteBuffer cachedCommBuffer;
  protected Socket socket = null;
  private SocketChannel socketChannel = null;
  private OutputStream outputStream = null;
  protected InputStream inputStream = null;
  private boolean messageModified = true;

  /** is this message a retry of a previously sent message? */
  private boolean isRetry;

  private byte flags = 0x00;
  MessageStats messageStats = null;
  protected ServerConnection serverConnection = null;
  private int maxIncomingMessageLength = -1;
  private Semaphore dataLimiter = null;
  private Semaphore messageLimiter = null;
  private boolean readHeader = false;
  private int chunkSize = DEFAULT_CHUNK_SIZE;

  Part securePart = null;
  private boolean isMetaRegion = false;

  private KnownVersion version;

  /**
   * Creates a new message with the given number of parts
   */
  public Message(int numberOfParts, KnownVersion destVersion) {
    version = destVersion;
    Assert.assertTrue(destVersion != null, "Attempt to create an unversioned message");
    partsList = new Part[numberOfParts];
    this.numberOfParts = numberOfParts;
    int partsListLength = partsList.length;
    for (int i = 0; i < partsListLength; i++) {
      partsList[i] = new Part();
    }
  }

  public boolean isSecureMode() {
    return securePart != null;
  }

  public byte[] getSecureBytes() throws IOException, ClassNotFoundException {
    return (byte[]) securePart.getObject();
  }

  public void setMessageType(int msgType) {
    messageModified = true;
    if (!MessageType.validate(msgType)) {
      throw new IllegalArgumentException(
          "Invalid MessageType");
    }
    messageType = msgType;
  }

  public void setVersion(KnownVersion clientVersion) {
    version = clientVersion;
  }

  public void setMessageHasSecurePartFlag() {
    flags |= MESSAGE_HAS_SECURE_PART;
  }

  public void clearMessageHasSecurePartFlag() {
    flags &= MESSAGE_HAS_SECURE_PART;
  }

  /**
   * Sets and builds the {@link Part}s that are sent in the payload of the Message
   */
  public void setNumberOfParts(int numberOfParts) {
    // hitesh: need to add security header here from server
    // need to insure it is not chunked message
    // should we look message type to avoid internal message like ping
    messageModified = true;
    currentPart = 0;
    this.numberOfParts = numberOfParts;
    if (numberOfParts > partsList.length) {
      Part[] newPartsList = new Part[numberOfParts];
      for (int i = 0; i < numberOfParts; i++) {
        if (i < partsList.length) {
          newPartsList[i] = partsList[i];
        } else {
          newPartsList[i] = new Part();
        }
      }
      partsList = newPartsList;
    }
  }

  /**
   * For boundary testing we may need to inject mock parts. For testing only.
   */
  void setParts(Part[] parts) {
    partsList = parts;
  }

  public void setTransactionId(int transactionId) {
    messageModified = true;
    this.transactionId = transactionId;
  }

  public void setIsRetry() {
    isRetry = true;
  }

  /**
   * This returns true if the message has been marked as having been previously transmitted to a
   * different server.
   */
  public boolean isRetry() {
    return isRetry;
  }

  /* Sets size for HDOS chunk. */
  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize;
  }

  /**
   * When building a Message this will return the number of the next Part to be added to the message
   */
  int getNextPartNumber() {
    return currentPart;
  }

  public void addStringPart(String str) {
    addStringPart(str, false);
  }

  @MakeNotStatic("not tied to the cache lifecycle")
  private static final Map<String, byte[]> CACHED_STRINGS = new ConcurrentHashMap<>();

  public void addStringPart(String str, boolean enableCaching) {
    if (str == null) {
      addRawPart(null, false);
      return;
    }

    Part part = partsList[currentPart];
    if (enableCaching) {
      byte[] bytes = CACHED_STRINGS.get(str);
      if (bytes == null) {
        try (HeapDataOutputStream hdos = new HeapDataOutputStream(str)) {
          bytes = hdos.toByteArray();
          CACHED_STRINGS.put(str, bytes);
        }
      }
      part.setPartState(bytes, false);

    } else {
      // do NOT close the HeapDataOutputStream
      messageModified = true;
      part.setPartState(new HeapDataOutputStream(str), false);
    }
    currentPart++;
  }

  /*
   * Adds a new part to this message that contains a {@code byte} array (as opposed to a serialized
   * object).
   *
   * @see #addPart(byte[], boolean)
   */
  public void addBytesPart(byte[] newPart) {
    addRawPart(newPart, false);
  }

  public void addStringOrObjPart(Object o) {
    if (o instanceof String || o == null) {
      addStringPart((String) o);
    } else {
      // Note even if o is a byte[] we need to serialize it.
      // This could be cleaned up but it would require C client code to change.
      serializeAndAddPart(o, false);
    }
  }

  public void addObjPart(Object o) {
    addObjPart(o, false);
  }

  /**
   * Like addObjPart(Object) but also prefers to reference objects in the part instead of copying
   * them into a byte buffer.
   */
  public void addObjPartNoCopying(Object o) {
    if (o == null || o instanceof byte[]) {
      addRawPart((byte[]) o, false);
    } else {
      serializeAndAddPartNoCopying(o);
    }
  }

  public void addObjPart(Object o, boolean zipValues) {
    if (o == null || o instanceof byte[]) {
      addRawPart((byte[]) o, false);
    } else if (o instanceof Boolean) {
      addRawPart((Boolean) o ? TRUE : FALSE, true);
    } else {
      serializeAndAddPart(o, zipValues);
    }
  }

  /**
   * Object o is always null
   */
  public void addPartInAnyForm(@Unretained Object o, boolean isObject) {
    if (o == null) {
      addRawPart((byte[]) o, false);
    } else if (o instanceof byte[]) {
      addRawPart((byte[]) o, isObject);
    } else if (o instanceof StoredObject) {
      // It is possible it is an off-heap StoredObject that contains a simple non-object byte[].
      messageModified = true;
      Part part = partsList[currentPart];
      part.setPartState((StoredObject) o, isObject);
      currentPart++;
    } else {
      serializeAndAddPart(o, false);
    }
  }

  private void serializeAndAddPartNoCopying(Object o) {
    KnownVersion v = version;
    if (version.equals(KnownVersion.CURRENT)) {
      v = null;
    }

    // Create the HDOS with a flag telling it that it can keep any byte[] or ByteBuffers/ByteSources
    // passed to it. Do NOT close the HeapDataOutputStream!
    HeapDataOutputStream hdos = new HeapDataOutputStream(chunkSize, v, true);
    try {
      BlobHelper.serializeTo(o, hdos);
    } catch (IOException ex) {
      throw new SerializationException("failed serializing object", ex);
    }
    messageModified = true;
    Part part = partsList[currentPart];
    part.setPartState(hdos, true);
    currentPart++;
  }

  private void serializeAndAddPart(Object o, boolean zipValues) {
    if (zipValues) {
      throw new UnsupportedOperationException("zipValues no longer supported");
    }

    KnownVersion v = version;
    if (version.equals(KnownVersion.CURRENT)) {
      v = null;
    }

    // do NOT close the HeapDataOutputStream
    HeapDataOutputStream hdos = new HeapDataOutputStream(chunkSize, v);
    try {
      BlobHelper.serializeTo(o, hdos);
    } catch (IOException ex) {
      throw new SerializationException("failed serializing object", ex);
    }
    messageModified = true;
    Part part = partsList[currentPart];
    part.setPartState(hdos, true);
    currentPart++;
  }

  public void addIntPart(int v) {
    messageModified = true;
    Part part = partsList[currentPart];
    part.setInt(v);
    currentPart++;
  }

  public void addLongPart(long v) {
    messageModified = true;
    Part part = partsList[currentPart];
    part.setLong(v);
    currentPart++;
  }

  public void addBytePart(byte v) {
    messageModified = true;
    Part part = partsList[currentPart];
    part.setByte(v);
    currentPart++;
  }

  /**
   * Adds a new part to this message that may contain a serialized object.
   */
  public void addRawPart(byte[] newPart, boolean isObject) {
    messageModified = true;
    Part part = partsList[currentPart];
    part.setPartState(newPart, isObject);
    currentPart++;
  }

  public int getMessageType() {
    return messageType;
  }

  public int getPayloadLength() {
    return payloadLength;
  }

  public int getHeaderLength() {
    return FIXED_LENGTH;
  }

  public int getNumberOfParts() {
    return numberOfParts;
  }

  public int getTransactionId() {
    return transactionId;
  }

  public Part getPart(int index) {
    if (index < numberOfParts) {
      Part p = partsList[index];
      if (version != null) {
        p.setVersion(version);
      }
      return p;
    }
    return null;
  }

  public static ByteBuffer setTLCommBuffer(ByteBuffer bb) {
    ByteBuffer result = tlCommBuffer.get();
    tlCommBuffer.set(bb);
    return result;
  }

  public ByteBuffer getCommBuffer() {
    if (cachedCommBuffer != null) {
      return cachedCommBuffer;
    } else {
      return tlCommBuffer.get();
    }
  }

  public void clear() {
    isRetry = false;
    int len = payloadLength;
    if (len != 0) {
      payloadLength = 0;
    }
    if (readHeader) {
      if (messageStats != null) {
        messageStats.decMessagesBeingReceived(len);
      }
    }
    ByteBuffer buffer = getCommBuffer();
    if (buffer != null) {
      buffer.clear();
    }
    clearParts();
    if (len != 0 && dataLimiter != null) {
      dataLimiter.release(len);
      dataLimiter = null;
      maxIncomingMessageLength = 0;
    }
    if (readHeader) {
      if (messageLimiter != null) {
        messageLimiter.release(1);
        messageLimiter = null;
      }
      readHeader = false;
    }
    flags = 0;
  }

  protected void packHeaderInfoForSending(int msgLen, boolean isSecurityHeader) {
    // setting second bit of flags byte for client this is not require but this makes all changes
    // easily at client side right now just see this bit and process security header
    byte flagsByte = flags;
    if (isSecurityHeader) {
      flagsByte |= MESSAGE_HAS_SECURE_PART;
    }
    if (isRetry) {
      flagsByte |= MESSAGE_IS_RETRY;
    }
    getCommBuffer().putInt(messageType).putInt(msgLen).putInt(numberOfParts)
        .putInt(transactionId).put(flagsByte);
  }

  protected Part getSecurityPart() {
    if (serverConnection != null) {
      // look types right put get etc
      return serverConnection.updateAndGetSecurityPart();
    }
    return null;
  }

  public void setSecurePart(byte[] bytes) {
    securePart = new Part();
    securePart.setPartState(bytes, false);
  }

  public void setMetaRegion(boolean isMetaRegion) {
    this.isMetaRegion = isMetaRegion;
  }

  boolean getAndResetIsMetaRegion() {
    boolean isMetaRegion = this.isMetaRegion;
    this.isMetaRegion = false;
    return isMetaRegion;
  }

  /**
   * Sends this message out on its socket.
   */
  void sendBytes(boolean clearMessage) throws IOException {
    if (serverConnection != null) {
      // Keep track of the fact that we are making progress.
      serverConnection.updateProcessingMessage();
    }
    if (socket == null) {
      throw new IOException("Dead Connection");
    }
    try {
      final ByteBuffer commBuffer = getCommBuffer();
      if (commBuffer == null) {
        throw new IOException("No buffer");
      }
      synchronized (commBuffer) {
        long totalPartLen = 0;
        long headerLen = 0;
        int partsToTransmit = numberOfParts;

        for (int i = 0; i < numberOfParts; i++) {
          Part part = partsList[i];
          headerLen += PART_HEADER_SIZE;
          totalPartLen += part.getLength();
        }

        Part securityPart = getSecurityPart();
        if (securityPart == null) {
          securityPart = securePart;
        }
        if (securityPart != null) {
          headerLen += PART_HEADER_SIZE;
          totalPartLen += securityPart.getLength();
          partsToTransmit++;
        }

        if (headerLen + totalPartLen > Integer.MAX_VALUE) {
          throw new MessageTooLargeException(
              "Message size (" + (headerLen + totalPartLen) + ") exceeds maximum integer value");
        }

        int msgLen = (int) (headerLen + totalPartLen);

        if (msgLen > maxMessageSize) {
          throw new MessageTooLargeException("Message size (" + msgLen
              + ") exceeds gemfire.client.max-message-size setting (" + maxMessageSize + ")");
        }

        commBuffer.clear();
        packHeaderInfoForSending(msgLen, securityPart != null);
        for (int i = 0; i < partsToTransmit; i++) {
          Part part = i == numberOfParts ? securityPart : partsList[i];

          if (commBuffer.remaining() < PART_HEADER_SIZE) {
            flushBuffer();
          }

          int partLen = part.getLength();
          commBuffer.putInt(partLen);
          commBuffer.put(part.getTypeCode());
          if (partLen <= commBuffer.remaining()) {
            part.writeTo(commBuffer);
          } else {
            flushBuffer();
            if (socketChannel != null) {
              part.writeTo(socketChannel, commBuffer);
            } else {
              part.writeTo(outputStream, commBuffer);
            }
            if (messageStats != null) {
              messageStats.incSentBytes(partLen);
            }
          }
        }
        if (commBuffer.position() != 0) {
          flushBuffer();
        }
        messageModified = false;
        if (socketChannel == null) {
          outputStream.flush();
        }
      }
    } finally {
      if (clearMessage) {
        clearParts();
      }
    }
  }

  void flushBuffer() throws IOException {
    final ByteBuffer cb = getCommBuffer();
    if (socketChannel != null) {
      cb.flip();
      do {
        socketChannel.write(cb);
      } while (cb.remaining() > 0);
    } else {
      outputStream.write(cb.array(), 0, cb.position());
    }
    if (messageStats != null) {
      messageStats.incSentBytes(cb.position());
    }
    cb.clear();
  }

  private void readHeaderAndBody(boolean setHeaderReadTimeout, int headerReadTimeoutMillis)
      throws IOException {
    clearParts();
    // TODO: for server changes make sure sc is not null as this class also used by client

    int oldTimeout = -1;
    if (setHeaderReadTimeout) {
      oldTimeout = socket.getSoTimeout();
      socket.setSoTimeout(headerReadTimeoutMillis);
    }
    try {
      fetchHeader();
    } finally {
      if (setHeaderReadTimeout) {
        socket.setSoTimeout(oldTimeout);
      }
    }

    final ByteBuffer cb = getCommBuffer();
    final int type = cb.getInt();
    final int len = cb.getInt();
    final int numParts = cb.getInt();
    final int txid = cb.getInt();
    byte bits = cb.get();
    cb.clear();

    if (!MessageType.validate(type)) {
      throw new IOException(String.format("Invalid message type %s while reading header",
          type));
    }

    int timeToWait = 0;
    if (serverConnection != null) {
      // Keep track of the fact that a message is being processed.
      serverConnection.setProcessingMessage();
      timeToWait = serverConnection.getClientReadTimeout();
    }
    readHeader = true;

    if (messageLimiter != null) {
      for (;;) {
        serverConnection.getCachedRegionHelper().checkCancelInProgress(null);
        boolean interrupted = Thread.interrupted();
        try {
          if (timeToWait == 0) {
            messageLimiter.acquire(1);
          } else {
            if (!messageLimiter.tryAcquire(1, timeToWait, TimeUnit.MILLISECONDS)) {
              if (messageStats instanceof CacheServerStats) {
                ((CacheServerStats) messageStats).incConnectionsTimedOut();
              }
              throw new IOException(
                  String.format(
                      "Operation timed out on server waiting on concurrent message limiter after waiting %s milliseconds",
                      timeToWait));
            }
          }
          break;
        } catch (InterruptedException ignore) {
          interrupted = true;
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      } // for
    }

    if (len > 0) {
      if (maxIncomingMessageLength > 0 && len > maxIncomingMessageLength) {
        throw new IOException(String.format("Message size %s exceeded max limit of %s",
            len, maxIncomingMessageLength));
      }

      if (dataLimiter != null) {
        for (;;) {
          if (serverConnection != null) {
            serverConnection.getCachedRegionHelper().checkCancelInProgress(null);
          }
          boolean interrupted = Thread.interrupted();
          try {
            if (timeToWait == 0) {
              dataLimiter.acquire(len);
            } else {
              int newTimeToWait = timeToWait;
              if (messageLimiter != null) {
                // may have waited for msg limit so recalc time to wait
                newTimeToWait -= (int) serverConnection.getCurrentMessageProcessingTime();
              }
              if (newTimeToWait <= 0
                  || !messageLimiter.tryAcquire(1, newTimeToWait, TimeUnit.MILLISECONDS)) {
                throw new IOException(
                    String.format(
                        "Operation timed out on server waiting on concurrent data limiter after waiting %s milliseconds",
                        timeToWait));
              }
            }
            // makes sure payloadLength gets set now so we will release the semaphore
            payloadLength = len;
            break; // success
          } catch (InterruptedException ignore) {
            interrupted = true;
          } finally {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    }
    if (messageStats != null) {
      messageStats.incMessagesBeingReceived(len);
      payloadLength = len; // makes sure payloadLength gets set now so we will dec on clear
    }

    isRetry = (bits & MESSAGE_IS_RETRY) != 0;
    bits &= MESSAGE_IS_RETRY_MASK;
    flags = bits;
    messageType = type;

    readPayloadFields(numParts, len);

    // Set the header and payload fields only after receiving all the
    // socket data, providing better message consistency in the face
    // of exceptional conditions (e.g. IO problems, timeouts etc.)
    payloadLength = len;
    // this.numberOfParts = numParts; Already set in setPayloadFields via setNumberOfParts
    transactionId = txid;
    flags = bits;
    if (serverConnection != null) {
      // Keep track of the fact that a message is being processed.
      serverConnection.updateProcessingMessage();
    }
  }

  /**
   * Read the actual bytes of the header off the socket
   */
  void fetchHeader() throws IOException {
    final ByteBuffer cb = getCommBuffer();
    cb.clear();

    // messageType is invalidated here and can be used as an indicator
    // of problems reading the message
    messageType = MessageType.INVALID;

    final int headerLength = getHeaderLength();
    if (socketChannel != null) {
      cb.limit(headerLength);
      do {
        int bytesRead = socketChannel.read(cb);
        if (bytesRead == -1) {
          throw new EOFException(
              "The connection has been reset while reading the header");
        }
        if (messageStats != null) {
          messageStats.incReceivedBytes(bytesRead);
        }
      } while (cb.remaining() > 0);
      cb.flip();

    } else {
      int hdr = 0;
      do {
        int bytesRead = inputStream.read(cb.array(), hdr, headerLength - hdr);
        if (bytesRead == -1) {
          throw new EOFException(
              "The connection has been reset while reading the header");
        }
        hdr += bytesRead;
        if (messageStats != null) {
          messageStats.incReceivedBytes(bytesRead);
        }
      } while (hdr < headerLength);

      // now setup the commBuffer for the caller to parse it
      cb.rewind();
    }
  }

  /**
   * TODO: refactor overly long method readPayloadFields
   */
  void readPayloadFields(final int numParts, final int len) throws IOException {
    if (len > 0 && numParts <= 0 || len <= 0 && numParts > 0) {
      throw new IOException(
          String.format("Part length ( %s ) and number of parts ( %s ) inconsistent",
              len, numParts));
    }

    Integer msgType = MESSAGE_TYPE.get();
    if (msgType != null && msgType == MessageType.PING) {
      // set it to null right away.
      MESSAGE_TYPE.set(null);
      // Some number which will not throw OOM but still be acceptable for a ping operation.
      int pingParts = 10;
      if (numParts > pingParts) {
        throw new IOException("Part length ( " + numParts + " ) is  inconsistent for "
            + MessageType.getString(msgType) + " operation.");
      }
    }

    setNumberOfParts(numParts);
    if (numParts <= 0) {
      return;
    }

    if (len < 0) {
      logger.info("rpl: neg len: {}", len);
      throw new IOException("Dead Connection");
    }

    final ByteBuffer cb = getCommBuffer();
    cb.clear();
    cb.flip();

    int readSecurePart = checkAndSetSecurityPart();

    int bytesRemaining = len;
    for (int i = 0; i < numParts + readSecurePart
        || readSecurePart == 1 && cb.remaining() > 0; i++) {
      int bytesReadThisTime = readPartChunk(bytesRemaining);
      bytesRemaining -= bytesReadThisTime;

      Part part;

      if (i < numParts) {
        part = partsList[i];
      } else {
        part = securePart;
      }

      int partLen = cb.getInt();
      byte partType = cb.get();
      byte[] partBytes = null;

      if (partLen > 0) {
        partBytes = new byte[partLen];
        int alreadyReadBytes = cb.remaining();
        if (alreadyReadBytes > 0) {
          if (partLen < alreadyReadBytes) {
            alreadyReadBytes = partLen;
          }
          cb.get(partBytes, 0, alreadyReadBytes);
        }

        // now we need to read partLen - alreadyReadBytes off the wire
        int off = alreadyReadBytes;
        int remaining = partLen - off;
        while (remaining > 0) {
          if (socketChannel != null) {
            int bytesThisTime = remaining;
            cb.clear();
            if (bytesThisTime > cb.capacity()) {
              bytesThisTime = cb.capacity();
            }
            cb.limit(bytesThisTime);
            int res = socketChannel.read(cb);
            if (res != -1) {
              cb.flip();
              bytesRemaining -= res;
              remaining -= res;
              cb.get(partBytes, off, res);
              off += res;
              if (messageStats != null) {
                messageStats.incReceivedBytes(res);
              }
            } else {
              throw new EOFException(
                  "The connection has been reset while reading a part");
            }
          } else {
            int res = inputStream.read(partBytes, off, remaining);
            if (res != -1) {
              bytesRemaining -= res;
              remaining -= res;
              off += res;
              if (messageStats != null) {
                messageStats.incReceivedBytes(res);
              }
            } else {
              throw new EOFException(
                  "The connection has been reset while reading a part");
            }
          }
        }
      }
      part.init(partBytes, partType);
    }
  }

  protected int checkAndSetSecurityPart() {
    if ((flags | MESSAGE_HAS_SECURE_PART) == flags) {
      securePart = new Part();
      return 1;
    } else {
      securePart = null;
      return 0;
    }
  }

  /**
   * @param bytesRemaining the most bytes we can read
   * @return the number of bytes read into commBuffer
   */
  private int readPartChunk(int bytesRemaining) throws IOException {
    final ByteBuffer commBuffer = getCommBuffer();
    if (commBuffer.remaining() >= PART_HEADER_SIZE) {
      // we already have the next part header in commBuffer so just return
      return 0;
    }

    if (commBuffer.position() != 0) {
      commBuffer.compact();
    } else {
      commBuffer.position(commBuffer.limit());
      commBuffer.limit(commBuffer.capacity());
    }

    if (serverConnection != null) {
      // Keep track of the fact that we are making progress
      serverConnection.updateProcessingMessage();
    }
    int bytesRead = 0;

    if (socketChannel != null) {
      int remaining = commBuffer.remaining();
      if (remaining > bytesRemaining) {
        remaining = bytesRemaining;
        commBuffer.limit(commBuffer.position() + bytesRemaining);
      }
      while (remaining > 0) {
        int res = socketChannel.read(commBuffer);
        if (res != -1) {
          remaining -= res;
          bytesRead += res;
          if (messageStats != null) {
            messageStats.incReceivedBytes(res);
          }
        } else {
          throw new EOFException(
              "The connection has been reset while reading the payload");
        }
      }

    } else {
      int bytesToRead = commBuffer.capacity() - commBuffer.position();
      if (bytesRemaining < bytesToRead) {
        bytesToRead = bytesRemaining;
      }
      int pos = commBuffer.position();

      while (bytesToRead > 0) {
        int res = inputStream.read(commBuffer.array(), pos, bytesToRead);
        if (res != -1) {
          bytesToRead -= res;
          pos += res;
          bytesRead += res;
          if (messageStats != null) {
            messageStats.incReceivedBytes(res);
          }
        } else {
          throw new EOFException(
              "The connection has been reset while reading the payload");
        }
      }

      commBuffer.position(pos);
    }
    commBuffer.flip();
    return bytesRead;
  }

  /**
   * Gets rid of all the parts that have been added to this message.
   */
  public void clearParts() {
    for (Part part : partsList) {
      part.clear();
    }
    currentPart = 0;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("type=").append(MessageType.getString(messageType));
    sb.append("; payloadLength=").append(payloadLength);
    sb.append("; numberOfParts=").append(numberOfParts);
    sb.append("; hasSecurePart=").append(isSecureMode());
    sb.append("; transactionId=").append(transactionId);
    sb.append("; currentPart=").append(currentPart);
    sb.append("; messageModified=").append(messageModified);
    sb.append("; flags=").append(Integer.toHexString(flags));
    for (int i = 0; i < numberOfParts; i++) {
      sb.append("; part[").append(i).append("]={");
      sb.append(partsList[i]);
      sb.append("}");
    }
    return sb.toString();
  }

  // Set up a message on the server side.
  void setComms(ServerConnection sc, Socket socket, ByteBuffer bb, MessageStats msgStats)
      throws IOException {
    serverConnection = sc;
    setComms(socket, bb, msgStats);
  }

  // Set up a message on the client side.
  void setComms(Socket socket, ByteBuffer bb, MessageStats msgStats) throws IOException {
    socketChannel = socket.getChannel();
    if (socketChannel == null) {
      setComms(socket, socket.getInputStream(), socket.getOutputStream(), bb, msgStats);
    } else {
      setComms(socket, null, null, bb, msgStats);
    }
  }

  // Set up a message on the client side.
  public void setComms(Socket socket, InputStream is, OutputStream os, ByteBuffer bb,
      MessageStats msgStats) {
    Assert.assertTrue(socket != null);
    this.socket = socket;
    socketChannel = socket.getChannel();
    inputStream = is;
    outputStream = os;
    cachedCommBuffer = bb;
    messageStats = msgStats;
  }

  /**
   * Undo any state changes done by setComms.
   *
   * @since GemFire 5.7
   */
  public void unsetComms() {
    socket = null;
    socketChannel = null;
    inputStream = null;
    outputStream = null;
    cachedCommBuffer = null;
    messageStats = null;
  }

  /**
   * Sends this message to its receiver over its setOutputStream?? output stream.
   */
  public void send() throws IOException {
    send(true);
  }

  public void send(ServerConnection servConn) throws IOException {
    if (serverConnection != servConn) {
      throw new IllegalStateException("this.sc was not correctly set");
    }
    send(true);
  }

  /**
   * Sends this message to its receiver over its setOutputStream?? output stream.
   */
  public void send(boolean clearMessage) throws IOException {
    sendBytes(clearMessage);
  }

  /**
   * Read a message, populating the state of this {@code Message} with information received via its
   * socket
   *
   * @param timeoutMillis timeout setting for reading the header (0 = no timeout)
   */
  public void receiveWithHeaderReadTimeout(int timeoutMillis) throws IOException {
    if (socket != null) {
      synchronized (getCommBuffer()) {
        readHeaderAndBody(true, timeoutMillis);
      }
    } else {
      throw new IOException("Dead Connection");
    }
  }

  /**
   * Populates the state of this {@code Message} with information received via its socket
   */
  public void receive() throws IOException {
    if (socket != null) {
      synchronized (getCommBuffer()) {
        readHeaderAndBody(false, -1);
      }
    } else {
      throw new IOException("Dead Connection");
    }
  }

  public void receive(ServerConnection sc, int maxMessageLength, Semaphore dataLimiter,
      Semaphore msgLimiter) throws IOException {
    serverConnection = sc;
    maxIncomingMessageLength = maxMessageLength;
    this.dataLimiter = dataLimiter;
    messageLimiter = msgLimiter;
    receive();
  }

}
