package org.example.ky;

/**
 * 白洞检测结果。
 *
 * <p>{@code holeMask} 中 {@code true} 表示该像素属于可放置商品的洞区域，
 * {@code bbox} 是该 mask 的最小外接矩形。</p>
 */
public class HoleResult {
    public final boolean[] holeMask;
    public final Rect bbox;
    public final int width;
    public final int height;

    public HoleResult(boolean[] holeMask, Rect bbox, int width, int height) {
        this.holeMask = holeMask;
        this.bbox = bbox;
        this.width = width;
        this.height = height;
    }
}
