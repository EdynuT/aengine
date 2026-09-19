package com.aengine.graphics;

public interface TextureAPI {
    void bind(int slot);
    void unbind();
    int  getWidth();
    int  getHeight();
    int  getID();
    void cleanup();
}
