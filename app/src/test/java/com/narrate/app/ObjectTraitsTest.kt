package com.narrate.app

import com.narrate.app.engine.ObjectTraits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An image model does not evaluate conditionals, so the app has to know what kind of thing it
 * is asking for before it writes the prompt.
 */
class ObjectTraitsTest {

    @Test
    fun `things that carry a picture of their owner are recognised`() {
        listOf(
            "Hospital ID badge", "identity card", "Passport", "driver's licence",
            "press pass", "a photograph of the crew", "silver locket", "security pass"
        ).forEach {
            assertTrue("$it should carry a likeness", ObjectTraits.bearsOwnerLikeness(it))
        }
    }

    @Test
    fun `ordinary objects do not carry anybody's face`() {
        listOf(
            "Medical reference book", "coil of rope", "leather satchel", "scalpel",
            "wristwatch", "bottle of whisky", "car keys", "umbrella"
        ).forEach {
            assertFalse("$it should not carry a likeness", ObjectTraits.bearsOwnerLikeness(it))
        }
    }

    @Test
    fun `a description can reveal what a vaguely named object is`() {
        assertTrue(
            ObjectTraits.bearsOwnerLikeness("keepsake", "a locket with her photograph inside")
        )
        assertFalse(ObjectTraits.bearsOwnerLikeness("keepsake", "a smooth grey pebble"))
    }

    @Test
    fun `objects made of writing are recognised`() {
        listOf(
            "Medical reference book", "Hospital ID badge", "case file", "handwritten letter",
            "newspaper", "prescription pad", "train ticket", "street map", "certificate"
        ).forEach {
            assertTrue("$it should allow writing", ObjectTraits.bearsText(it))
        }
    }

    @Test
    fun `objects with nothing written on them stay wordless`() {
        listOf("coil of rope", "scalpel", "umbrella", "silver ring", "bottle of whisky").forEach {
            assertFalse("$it should not invite writing", ObjectTraits.bearsText(it))
        }
    }

    @Test
    fun `anything bearing a likeness also bears the writing that goes with it`() {
        // A badge with a photograph and no name on it is not a badge.
        assertTrue(ObjectTraits.bearsText("Hospital ID badge"))
        assertTrue(ObjectTraits.bearsText("passport"))
    }

    @Test
    fun `an unnamed object claims nothing`() {
        assertFalse(ObjectTraits.bearsOwnerLikeness(""))
        assertFalse(ObjectTraits.bearsText(""))
    }
}
