package com.citypass.storage;

import java.io.InputStream;

/**
 * Keys always originate in DB records; callers never accept arbitrary client keys.
 */
public interface ObjectStorage {

    String signPut(String key, int seconds) throws Exception;

    String signGet(String key, int seconds) throws Exception;

    long size(String key) throws Exception;

    InputStream open(String key) throws Exception;

    void copy(String stagingKey, String finalKey) throws Exception;

    void delete(String key) throws Exception;
}
