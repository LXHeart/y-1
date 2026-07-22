package com.grassland.storage;

/** 对象存储领域异常，包装底层 SDK 的非预期错误。 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
