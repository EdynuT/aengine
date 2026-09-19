package com.aengine.graphics;

import org.joml.Vector4f;

public interface RendererAPI {
    void init();
    void initBatchBuffers(int maxVertices, int maxIndices, int vertexSizeFloats);
    void setClearColor(Vector4f color);
    void clear();
    void drawBatch(float[] vertices, int vertexCount, int indexCount);
    void cleanup();
}
