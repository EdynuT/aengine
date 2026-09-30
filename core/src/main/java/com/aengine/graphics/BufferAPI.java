package com.aengine.graphics;

/**
 * A GPU-side buffer or buffer layout that can be made current, released, and nothing more.
 * Uploading data is backend-specific and lives on the implementations.
 */
public interface BufferAPI {
    /** Makes this buffer the current one for its kind, replacing whatever was bound. */
    void bind();
    /** Clears the binding for this buffer's kind. It does not check that this buffer was the one bound. */
    void unbind();
    /** Deletes the GPU object. The buffer must not be used afterwards. */
    void cleanup();
}
