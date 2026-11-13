package io.github.detach

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentWidgetProviderTest {
    private val widget = PaymentWidgetProvider()

    @Test fun `one-row widgets follow the agreed per-span capacities`() {
        assertShape(80, 90, PaymentWidgetProvider.Kind.STRIP, 1, 1, 1)
        assertShape(180, 90, PaymentWidgetProvider.Kind.STRIP, 3, 1, 3)
        assertShape(280, 90, PaymentWidgetProvider.Kind.STRIP, 4, 1, 4)
        assertShape(380, 90, PaymentWidgetProvider.Kind.STRIP, 5, 1, 5)
        assertShape(500, 90, PaymentWidgetProvider.Kind.STRIP, 5, 1, 5)
    }

    @Test fun `two-row widgets use exact agreed folder grids`() {
        assertShape(160, 150, PaymentWidgetProvider.Kind.GRID_3, 3, 3, 9)
        assertShape(240, 150, PaymentWidgetProvider.Kind.GRID_4, 4, 2, 8)
        assertShape(320, 150, PaymentWidgetProvider.Kind.GRID_5, 5, 2, 10)
    }

    private fun assertShape(width: Int, height: Int, kind: PaymentWidgetProvider.Kind, columns: Int, rows: Int, capacity: Int) {
        val shape = widget.shapeFor(width, height)
        assertEquals(kind, shape.kind)
        assertEquals(columns, shape.columns)
        assertEquals(rows, shape.rows)
        assertEquals(capacity, shape.capacity)
    }
}
