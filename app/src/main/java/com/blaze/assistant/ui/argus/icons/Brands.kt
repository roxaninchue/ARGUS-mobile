package com.blaze.assistant.ui.argus.icons

import com.blaze.assistant.brand.BrandPath
import com.blaze.assistant.brand.BrandPaths
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Model makers' logos for the model picker, from the generated [BrandPaths]; cached per (brand, ink). Draw with Image, not Icon (Icon's tint would flatten the colours). */
object Brands {
    private val cache = HashMap<String, ImageVector>()

    fun has(brand: String?): Boolean = brand != null && BrandPaths.ICONS.containsKey(brand)

    /** [brand]'s logo; paths with no colour of their own are drawn in [ink]. Null when there is no such brand. */
    fun icon(brand: String?, ink: Color): ImageVector? {
        val ps = BrandPaths.ICONS[brand ?: return null] ?: return null
        val ck = "$brand@${ink.value}"
        return synchronized(cache) {
            cache.getOrPut(ck) {
                val b = ImageVector.Builder(name = "brand-$brand", defaultWidth = 24.dp, defaultHeight = 24.dp,
                    viewportWidth = BrandPaths.VIEWPORT, viewportHeight = BrandPaths.VIEWPORT)
                for (p in ps) b.addPath(pathData = addPathNodes(p.d), pathFillType = PathFillType.EvenOdd,
                    fill = SolidColor(p.argb?.let { Color(it.toInt()) } ?: ink))
                b.build()
            }
        }
    }
}
