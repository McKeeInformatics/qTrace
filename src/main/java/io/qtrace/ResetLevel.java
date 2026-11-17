package io.qtrace;

/** How far Reset goes. Each level includes the ones before it. */
enum ResetLevel {
    /** The capture only: what qTrace recorded so far. The image's history and its .qtrace stay. */
    CAPTURE,
    /** Plus the image's own workflow history and the logger's memory: nothing comes back on reopening. */
    IMAGE,
    /** Plus the image's .qtrace, set aside (renamed, never deleted): the next stamp starts a new chain. */
    EVERYTHING;

    boolean emptiesImageHistory() { return this != CAPTURE; }
    boolean setsRecordAside()     { return this == EVERYTHING; }
}
