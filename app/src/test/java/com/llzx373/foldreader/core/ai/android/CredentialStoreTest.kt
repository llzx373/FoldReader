package com.llzx373.foldreader.core.ai.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CredentialStoreTest {

    private val store = CredentialStore(RuntimeEnvironment.getApplication())

    @Test
    fun `保存后可读回原值`() {
        store.saveKey("sk-test-123")

        assertEquals("sk-test-123", store.readKey())
    }

    @Test
    fun `未保存时读取为 null`() {
        store.clear()

        assertNull(store.readKey())
    }

    @Test
    fun `清除后读取为 null`() {
        store.saveKey("sk-test-123")
        store.clear()

        assertNull(store.readKey())
    }

    @Test
    fun `覆盖保存读回最新值`() {
        store.saveKey("sk-old")
        store.saveKey("sk-new")

        assertEquals("sk-new", store.readKey())
    }
}
