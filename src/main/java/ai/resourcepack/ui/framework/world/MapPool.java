package ai.resourcepack.ui.framework.world;

import java.util.Arrays;

/**
 * Walls whose frames may show one another's maps. For each player the sender keeps one map for each distinct tile
 * the pool's walls show them (see {@code MapSender}): the same page on two walls, or a plain stretch of one page, is
 * one map, sent once and drawn once. The client draws every distinct map with a draw call of its own every frame,
 * whatever its size on screen, and frames showing the same map share one, so this is frame rate as well as bytes.
 *
 * <p>Every map id of the pool's walls lies in [{@link #base}, {@link #end}).
 */
public final class MapPool {
    public final int base;
    private volatile MapWall[] walls = new MapWall[0];
    private volatile int end;

    public MapPool(int base) {
        this.base = base;
        this.end = base;
    }

    /** The walls in the pool now; never changed (a new array when one comes or goes). */
    public MapWall[] walls() {
        return walls;
    }

    /** One past the highest map id any wall of the pool has had. */
    public int end() {
        return end;
    }

    public synchronized void add(MapWall wall) {
        MapWall[] now = Arrays.copyOf(walls, walls.length + 1);
        now[now.length - 1] = wall;
        end = Math.max(end, wall.firstMapId + wall.tiles());
        wall.pool = this;
        walls = now;
    }

    public synchronized void remove(MapWall wall) {
        MapWall[] now = new MapWall[walls.length];
        int n = 0;
        for (MapWall w : walls) if (w != wall) now[n++] = w;
        walls = Arrays.copyOf(now, n);
    }
}
