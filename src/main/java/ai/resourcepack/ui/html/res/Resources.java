package ai.resourcepack.ui.html.res;

/** Where a page's images come from. Loading happens before rendering: this only looks them up. */
public interface Resources {
    /** The image at an absolute URI (or a data: URI), or null if it is missing or unreadable. */
    Picture image(String uri);

    /** An inline {@code <svg>}, its markup with currentColor already replaced. */
    Picture svg(String markup);

    Resources NONE = new Resources() {
        @Override
        public Picture image(String uri) {
            return null;
        }

        @Override
        public Picture svg(String markup) {
            return null;
        }
    };
}
