package ai.resourcepack.ui.html;

/** A render given up before it finished: its {@link RenderBudget} ran out. Nothing it drew is to be used. */
public final class RenderAbandoned extends RuntimeException {
    public RenderAbandoned(String message) {
        super(message);
    }
}
