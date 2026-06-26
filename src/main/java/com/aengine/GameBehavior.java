package com.aengine;

public interface GameBehavior {
    void init();
    void update(float deltaTime);
    void render();
    void cleanup();
}
