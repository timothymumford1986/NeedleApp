package app.needler.core.data.platform

import java.io.File

/**
 * How much disk the artwork cache is holding, and how to empty it.
 *
 * Artwork is reported on its own line in the Storage screen because it has its own small LRU and is
 * usually tiny: a user hunting for gigabytes should not spend a tap on it. The cache itself belongs
 * to the image loader, so this is the narrowest possible seam onto it - a size and a clear - rather
 * than a dependency on Coil from the data layer.
 */
public interface ArtworkCacheSize {

    public fun bytes(): Long

    public fun clear()

    public companion object {
        /** Reports nothing and clears nothing. The honest answer before the loader is wired up. */
        public val None: ArtworkCacheSize = object : ArtworkCacheSize {
            override fun bytes(): Long = 0L
            override fun clear(): Unit = Unit
        }
    }
}

/**
 * [ArtworkCacheSize] over a directory on disk, which is how every disk-backed image loader stores
 * its cache.
 *
 * Measuring walks the directory, so callers should not do it per frame; the Storage screen reads it
 * once per snapshot, which is what it is for.
 */
public class DirectoryArtworkCacheSize(
    private val directory: File,
) : ArtworkCacheSize {

    override fun bytes(): Long = runCatching {
        directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    override fun clear() {
        runCatching { directory.deleteRecursively() }
    }
}
