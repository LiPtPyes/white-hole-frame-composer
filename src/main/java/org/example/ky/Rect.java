package org.example.ky;

/**
 * 简单矩形模型，坐标采用左上角加宽高的形式。
 */
public class Rect {
    public final int x;
    public final int y;
    public final int w;
    public final int h;

    public Rect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public String toString() {
        return "Rect{x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + "}";
    }
}
