package cache.loaders

import cache.XteaManager
import cache.definitions.Location
import cache.definitions.LocationsDefinition
import cache.utils.readUnsignedShortSmart
import cache.utils.readUnsignedSmartShortExtended
import com.displee.cache.CacheLibrary
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

class LocationsLoader(
    private val library: CacheLibrary,
    private val xtea: XteaManager,
) : ThreadsafeLazyLoader<LocationsDefinition>() {
    private val logger = LoggerFactory.getLogger(LocationsLoader::class.java)

    override fun load(id: Int): LocationsDefinition? {
        val locationsDefinition = LocationsDefinition(id)
        val x = (id shr 8) and 0xFF
        val y = id and 0xFF
        val mapName = "l${x}_$y"
        val targetHash = calculateOsrsHash(mapName)

        val xteaKeys = xtea.getKeys(id) ?: IntArray(4)
        val mapsIndex = library.index(5)

        var landscape: ByteArray? = null

        // 1. Primary Method (Pre-Stripped Caches)
        landscape = library.data(5, mapName, xteaKeys)

        // 2. DJB2 Hash Lookup (OpenRS2 Master Caches - Pre Rev 237)
        if (landscape == null && mapsIndex != null) {
            val archiveByHash = mapsIndex.archive(targetHash)
            if (archiveByHash != null) {
                landscape = library.data(5, archiveByHash.id, 0, xteaKeys)
            }
        }

        // 3. REV 237 NATIVE SUPPORT: Archive ID == Region ID (Locations is File 1)
        if (landscape == null && mapsIndex != null) {
            if (id != 25287 && mapsIndex.archive(id) != null) {
                // Jagex removed XTEA encryption entirely in Rev 237!
                landscape = library.data(5, id, 1)
            }
        }

        if (landscape == null) {
            logger.warn("Locations $id: File not found.")
            return null
        }

        val buffer = ByteBuffer.wrap(landscape)
        var objId = -1
        var idOffset = buffer.readUnsignedSmartShortExtended()
        while (idOffset != 0) {
            objId += idOffset
            var position = 0
            var positionOffset = buffer.readUnsignedShortSmart()
            while (positionOffset != 0) {
                position += positionOffset - 1
                val localY = position and 0x3F
                val localX = position shr 6 and 0x3F
                val height = position shr 12 and 0x3
                val attributes = buffer.get().toInt()
                val type = attributes shr 2
                val orientation = attributes and 0x3

                locationsDefinition.locations.add(Location(objId, type, orientation, localX, localY, height))
                positionOffset = buffer.readUnsignedShortSmart()
            }
            idOffset = buffer.readUnsignedSmartShortExtended()
        }
        return locationsDefinition
    }

    private fun calculateOsrsHash(name: String): Int {
        var hash = 0
        for (element in name.lowercase()) {
            hash = (hash shl 5) - hash + element.code
        }
        return hash
    }
}