package cache.loaders

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
        val mapsIndex = cacheLibrary.index(5)
        val regionX = (id shr 8) and 0xFF
        val regionY = id and 0xFF
        val mapName = "m${regionX}_$regionY"
        val targetHash = calculateOsrsHash(mapName)

        var map: ByteArray? = null

        // 1. Try fetching by Name (Pre-Stripped Caches)
        map = cacheLibrary.data(5, mapName)

        // 2. Try fetching by DJB2 Hash (Standard OpenRS2 Caches)
        if (map == null && mapsIndex != null) {
            val archiveByHash = mapsIndex.archive(targetHash)
            if (archiveByHash != null) {
                map = cacheLibrary.data(5, archiveByHash.id, 0)
            }
        }

        // 3. REV 237 NATIVE SUPPORT: Archive ID == Region ID (Terrain is File 0)
        if (map == null && mapsIndex != null) {
            // Ignore the hardcoded dummy region 25287 (98 << 8 | 199)
            if (id != 25287 && mapsIndex.archive(id) != null) {
                map = cacheLibrary.data(5, id, 0)
            }
        }

        if (map == null) {
            logger.warn("Region $id: Terrain file not found.")
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

    private fun calculateOsrsHash(name: String): Int {
        var hash = 0
        for (element in name.lowercase()) {
            hash = (hash shl 5) - hash + element.code
        }
        return hash
    }

    fun findRegionForWorldCoordinates(x: Int, y: Int): RegionDefinition? {
        return get(Utils.worldCoordinatesToRegionId(x, y))
    }

    companion object {
        private const val OVERLAY_SHORT_BREAKING_CHANGE_REV_NUMBER = 209
    }
}