package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatesTest {
    @Test fun readsBuildFromTag() {
        assertEquals(52, Updates.buildOf("v52"))
        assertNull(Updates.buildOf("latest"))
        assertNull(Updates.buildOf("v1.2"))
    }
}
