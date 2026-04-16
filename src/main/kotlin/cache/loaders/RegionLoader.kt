package cache.loaders

import cache.IndexType
import cache.ParamType
import cache.ParamsManager
import cache.definitions.RegionDefinition
import cache.definitions.RegionDefinition.Companion.X
import cache.definitions.RegionDefinition.Companion.Y
import cache.definitions.RegionDefinition.Companion.Z
import cache.utils.readUnsignedByte
import cache.utils.readUnsignedShort
import com.displee.cache.CacheLibrary
import org.slf4j.LoggerFactory
import utils.Utils
import java.nio.ByteBuffer

class RegionLoader(
    private val cacheLibrary: CacheLibrary,
    private val paramsManager: ParamsManager
) : ThreadsafeLazyLoader<RegionDefinition>() {
    private val logger = LoggerFactory.getLogger(RegionLoader::class.java)
    private val readOverlayAsShort = (paramsManager.getParam(ParamType.REVISION)?.toInt() ?: 0) >= OVERLAY_SHORT_BREAKING_CHANGE_REV_NUMBER

    override fun load(id: Int): RegionDefinition? {
        val regionX = (id shr 8) and 0xFF
        val regionY = id and 0xFF
        val mapName = "m${regionX}_$regionY"

        // 1. Primary Method: Let the library find it by name
        var map = cacheLibrary.data(5, mapName)

        // 2. Fallback: If names are missing, read the Map Index (Index 2, Archive 5)
        if (map == null) {
            val mapIndexData = cacheLibrary.index(2)?.archive(5)?.file(0)?.data
            if (mapIndexData != null) {
                val buffer = ByteBuffer.wrap(mapIndexData)
                while (buffer.remaining() >= 7) {
                    val rId = buffer.short.toInt() and 0xFFFF
                    val tId = buffer.short.toInt() and 0xFFFF
                    buffer.short // Skip location ID
                    buffer.get()   // Skip isMembers

                    if (rId == id) {
                        map = cacheLibrary.data(5, tId, 0)
                        break
                    }
                }
            }
        }

        if (map == null) {
            logger.warn("Region $id: Terrain file not found (m${regionX}_$regionY).")
            return null
        }

        val inputStream = ByteBuffer.wrap(map)

        val tiles = Array(Z) {
            Array(X) {
                Array(Y) {
                    val tile = RegionDefinition.Tile()
                    while (true) {
                        val attribute: Int = if (readOverlayAsShort) inputStream.readUnsignedShort() else inputStream.readUnsignedByte()
                        if (attribute == 0) break
                        else if (attribute == 1) {
                            val height: Int = inputStream.readUnsignedByte()
                            tile.cacheHeight = height
                            tile.height = height
                            break
                        } else if (attribute <= 49) {
                            tile.attrOpcode = attribute
                            tile.overlayId = if (readOverlayAsShort) inputStream.short else inputStream.get().toShort()
                            tile.overlayPath = ((attribute - 2) / 4).toByte()
                            tile.overlayRotation = (attribute - 2 and 3).toByte()
                        } else if (attribute <= 81) {
                            tile.settings = (attribute - 49).toByte()
                        } else {
                            tile.underlayId = (attribute - 81).toShort()
                        }
                    }
                    tile
                }
            }
        }

        val regionDefinition = RegionDefinition(id, tiles)
        regionDefinition.calculateTerrain()
        return regionDefinition
    }

    fun findRegionForWorldCoordinates(x: Int, y: Int): RegionDefinition? {
        return get(Utils.worldCoordinatesToRegionId(x, y))
    }

    companion object {
        private const val OVERLAY_SHORT_BREAKING_CHANGE_REV_NUMBER = 209
    }
}