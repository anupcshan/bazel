// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote;

import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.remote.RemoteProtocol.CacheEntry;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileEntry;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.HashMap;

import com.hazelcast.internal.ascii.rest.RestValue;

/**
 * A RemoteActionCache implementation that uses memcache as a distributed storage
 * for files and action output. The memcache is accessed by the {@link ConcurrentMap}
 * interface.
 *
 * The thread satefy is guaranteed by the underlying memcache client.
 */
@ThreadSafe
final class MemcacheActionCache implements RemoteActionCache {
  private final Path execRoot;
  private final ConcurrentMap<String, byte[]> cache;
  private static final int MAX_MEMORY_KBYTES = 512 * 1024;
  private final Semaphore uploadMemoryAvailable = new Semaphore(MAX_MEMORY_KBYTES, true);
  private static final int LOCK_SHARDS = 16;
  private final Semaphore[] uploadLock;
  private final ConcurrentMap<String, Semaphore> uploadingKeys = new ConcurrentHashMap<String, Semaphore>();
  private final ListeningExecutorService cacheUploadService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(40));

  /**
   * Construct an action cache using JCache API.
   */
  MemcacheActionCache(
      Path execRoot, RemoteOptions options, ConcurrentMap<String, byte[]> cache) {
    this.execRoot = execRoot;
    this.cache = cache;
    this.uploadLock = new Semaphore[LOCK_SHARDS];
    for (int i = 0; i < LOCK_SHARDS; i ++) {
      this.uploadLock[i] = new Semaphore(1);
    }
  }

  @Override
  public ListenableFuture<String> putFileIfNotExist(Path file) throws IOException {
    String contentKey = HashCode.fromBytes(file.getMD5Digest()).toString();
    return putFileIfNotExist(contentKey, file);
  }

  @Override
  public ListenableFuture<String> putFileIfNotExist(ActionInputFileCache cache, ActionInput file) throws IOException {
    // PerActionFileCache already converted this to a lowercase ascii string.. it's not consistent!
    String contentKey = new String(cache.getDigest(file).toByteArray());
    return putFileIfNotExist(contentKey, execRoot.getRelative(file.getExecPathString()));
  }

  private ListenableFuture<String> putFileIfNotExist(String contentKey, Path file) throws IOException {
    return cacheUploadService.submit(new Callable<String>() {
      public String call() throws IOException {
        if (containsFile(contentKey)) {
          return contentKey;
        }
        Semaphore waitKey;
        int shard = contentKey.hashCode() % LOCK_SHARDS;
        shard = (shard + LOCK_SHARDS) % LOCK_SHARDS;
        try {
          uploadLock[shard].acquire(1);
          if (!uploadingKeys.containsKey(contentKey)) {
            uploadingKeys.put(contentKey, new Semaphore(1));
          }
          waitKey = uploadingKeys.get(contentKey);
        } catch (InterruptedException e) {
          throw new IOException("Failed to get lock for uploading.", e);
        } finally {
          uploadLock[shard].release(1);
        }

        try {
          waitKey.acquire(1);
          if (containsFile(contentKey)) {
            return contentKey;
          }
          putFile(contentKey, file);
        } catch (InterruptedException e) {
          throw new IOException("Failed to put file to memory cache.", e);
        } finally {
          waitKey.release(1);
        }
        return contentKey;
      }
    });
  }

  private void putFile(String key, Path file) throws IOException {
    // System.out.println("Uploading:" + key);
    int fileSizeKBytes = (int) (file.getFileSize() / 1024);
    Preconditions.checkArgument(fileSizeKBytes < MAX_MEMORY_KBYTES);
    try {
      uploadMemoryAvailable.acquire(fileSizeKBytes);
      // TODO(alpha): I should put the file content as chunks to avoid reading the entire
      // file into memory.
      try (InputStream stream = file.getInputStream()) {
        cache.putIfAbsent(
            key,
            CacheEntry.newBuilder()
                .setFileContent(ByteString.readFrom(stream))
                .build()
                .toByteArray());
      }
    } catch (InterruptedException e) {
      throw new IOException("Failed to put file to memory cache.", e);
    } finally {
      uploadMemoryAvailable.release(fileSizeKBytes);
    }
  }

  @Override
  public void writeFile(String key, Path dest, boolean executable)
      throws IOException, CacheNotFoundException {
    byte[] data;
    Object cachedData = cache.get(key);

    try {
      data = (byte[])cachedData;
    } catch (ClassCastException ce) {
      RestValue restValue = (RestValue) cachedData;
      data = restValue.getValue();
    }
    if (data == null) {
      throw new CacheNotFoundException("File content cannot be found with key: " + key);
    }
    try (OutputStream stream = dest.getOutputStream()) {
      CacheEntry.parseFrom(data).getFileContent().writeTo(stream);
      dest.setExecutable(executable);
    }
  }

  private boolean containsFile(String key) {
    return cache.containsKey(key);
  }

  @Override
  public void writeActionOutput(String key, Path execRoot)
      throws IOException, CacheNotFoundException {
    byte[] data;
    Object cachedData = cache.get(key);

    try {
      data = (byte[])cachedData;
    } catch (ClassCastException ce) {
      RestValue restValue = (RestValue) cachedData;
      data = restValue.getValue();
    }
    if (data == null) {
      throw new CacheNotFoundException("Action output cannot be found with key: " + key);
    }
    CacheEntry cacheEntry = CacheEntry.parseFrom(data);
    for (FileEntry file : cacheEntry.getFilesList()) {
      writeFile(file.getContentKey(), execRoot.getRelative(file.getPath()), file.getExecutable());
    }
  }

  @Override
  public void putActionOutput(String key, Collection<? extends ActionInput> outputs)
      throws IOException {
    CacheEntry.Builder actionOutput = CacheEntry.newBuilder();
    HashMap<ActionInput, ListenableFuture<String>> keyFutures = new HashMap<ActionInput, ListenableFuture<String>>();
    for (ActionInput output : outputs) {
      Path file = execRoot.getRelative(output.getExecPathString());
      keyFutures.put(output, putFileIfNotExist(file));
    }

    try {
      Futures.allAsList(keyFutures.values()).get();
    } catch (InterruptedException e) {
      throw new IOException("Failed to put file to memory cache.", e);
    } catch (ExecutionException e) {
      throw new IOException("Failed to put file to memory cache.", e);
    }

    for (ActionInput output : outputs) {
      Path file = execRoot.getRelative(output.getExecPathString());
      try {
        addToActionOutput(keyFutures.get(output).get(), output.getExecPathString(), actionOutput, file.isExecutable());
      } catch (InterruptedException e) {
        throw new IOException("Failed to put file to memory cache.", e);
      } catch (ExecutionException e) {
        throw new IOException("Failed to put file to memory cache.", e);
      }
    }
    cache.put(key, actionOutput.build().toByteArray());
  }

  @Override
  public void putActionOutput(String key, Path execRoot, Collection<Path> files)
      throws IOException {
    CacheEntry.Builder actionOutput = CacheEntry.newBuilder();
    HashMap<Path, ListenableFuture<String>> keyFutures = new HashMap<Path, ListenableFuture<String>>();
    for (Path file : files) {
      keyFutures.put(file, putFileIfNotExist(file));
    }

    try {
      Futures.allAsList(keyFutures.values()).get();
    } catch (InterruptedException e) {
      throw new IOException("Failed to put file to memory cache.", e);
    } catch (ExecutionException e) {
      throw new IOException("Failed to put file to memory cache.", e);
    }

    for (Path file : files) {
      try {
        addToActionOutput(keyFutures.get(file).get(), file.relativeTo(execRoot).getPathString(), actionOutput, file.isExecutable());
      } catch (InterruptedException e) {
        throw new IOException("Failed to put file to memory cache.", e);
      } catch (ExecutionException e) {
        throw new IOException("Failed to put file to memory cache.", e);
      }
    }
    cache.put(key, actionOutput.build().toByteArray());
  }

  /**
   * Add the file to action output cache entry. Put the file to cache if necessary.
   */
  private void addToActionOutput(String contentKey, String execPathString, CacheEntry.Builder actionOutput, boolean isExecutable)
      throws IOException {
    /*
    if (file.isDirectory()) {
      // TODO(alpha): Implement this for directory.
      throw new UnsupportedOperationException("Storing a directory is not yet supported.");
    }
    */

    // Add to protobuf.
    actionOutput
        .addFilesBuilder()
        .setPath(execPathString)
        .setContentKey(contentKey)
        .setExecutable(isExecutable);
  }
}
