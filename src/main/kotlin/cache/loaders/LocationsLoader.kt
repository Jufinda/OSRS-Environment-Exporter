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

        val xteaKeys = xtea.getKeys(id) ?: IntArray(4)

        // 1. Primary Method
        var landscape = library.data(5, mapName, xteaKeys)

        // 2. Fallback: Map Index Lookup
        if (landscape == null) {
            val mapIndexData = library.index(2)?.archive(5)?.file(0)?.data
            if (mapIndexData != null) {
                val buffer = ByteBuffer.wrap(mapIndexData)
                while (buffer.remaining() >= 7) {
                    val rId = buffer.short.toInt() and 0xFFFF
                    buffer.short // Skip terrain ID
                    val lId = buffer.short.toInt() and 0xFFFF
                    buffer.get()   // Skip isMembers

                    if (rId == id) {
                        landscape = library.data(5, lId, 0, xteaKeys)
                        break
                    }
                }
            }
        }

        if (landscape == null) {
            logger.warn("Locations $id: File not found (l${x}_$y).")
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
}