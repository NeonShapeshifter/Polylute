package com.lostf1sh.pixelplayeross.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.lostf1sh.pixelplayeross.ui.glass.B42Renders

/** B42 captures dialog windows; popup windows also need their WindowManager placement. */
internal object B52Renders {
    fun screen(compose: ComposeContentTestRule): Bitmap {
        B42Renders.settle(compose)
        val windows = compose.onAllNodes(isRoot())
        val windowNodes = windows.fetchSemanticsNodes()
        val activity = windowNodes.indexOfFirst { B42Renders.dialogWindow(it) == null }
        val out = windows[activity].captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        windowNodes.mapNotNull(B42Renders::dialogWindow).distinct().forEach { window ->
            val dim = window.attributes.dimAmount
            if(window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) canvas.drawColor(android.graphics.Color.argb((dim * 255).toInt(),0,0,0))
            val bitmap = B42Renders.windowOf(window)
            println("B52 dialog width=${bitmap.width} height=${bitmap.height} dim=$dim")
            canvas.drawBitmap(bitmap, (out.width-bitmap.width)/2f,(out.height-bitmap.height)/2f,null)
        }
        val roots = compose.onAllNodes(isPopup())
        val nodes = roots.fetchSemanticsNodes()
        nodes.forEachIndexed { index,node ->
            val view = (node.root as ViewRootForTest).view
            val params = view.layoutParams as? WindowManager.LayoutParams
                ?: view.rootView.layoutParams as? WindowManager.LayoutParams
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            val location = IntArray(2).also(view::getLocationOnScreen)
            val x = params?.x ?: location[0]
            val y = params?.y ?: location[1]
            println("B52 popup placement x=$x y=$y width=${bitmap.width} height=${bitmap.height}")
            canvas.drawBitmap(bitmap,x.toFloat(),y.toFloat(),null)
        }
        return out
    }
}
