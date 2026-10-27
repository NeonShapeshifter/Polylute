package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * B41-fix1 (B9): [androidx.compose.ui.input.pointer.pointerInput] that **shares** what it hears with the siblings under it.
 *
 * An ordinary pointer input claims the hit: a sibling drawn under a node that has one gets no touch at all where the two overlap, whether or
 * not the top one consumes anything. The scrollbar's target is 48 dp wide and lies over the end of the list, so with an ordinary handler a tap on
 * a row's trailing control or a scroll that starts near the edge would have gone nowhere. Shared, the bar looks first (it is on top), takes only
 * the touches that grab the thumb or the edge strip (it consumes them, and the list under it, which checks `isConsumed` before it scrolls, lets
 * them go), and leaves every other one, unconsumed, to the list.
 */
internal fun Modifier.sharedPointerInput(block: suspend PointerInputScope.() -> Unit): Modifier =
    this then SharedPointerInputElement(block)

private class SharedPointerInputElement(private val block: suspend PointerInputScope.() -> Unit) : ModifierNodeElement<SharedPointerInputNode>() {
    override fun create() = SharedPointerInputNode(block)

    // Like `pointerInput(Unit)`: the first block stays for the life of the node.
    override fun update(node: SharedPointerInputNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "sharedPointerInput"
    }

    override fun equals(other: Any?) = other is SharedPointerInputElement
    override fun hashCode() = SharedPointerInputElement::class.hashCode()
}

private class SharedPointerInputNode(block: suspend PointerInputScope.() -> Unit) : DelegatingNode(), PointerInputModifierNode {
    private val handler = delegate(SuspendingPointerInputModifierNode(block))

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        handler.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = handler.onCancelPointerInput()

    override fun sharePointerInputWithSiblings() = true
}
