package com.llzx373.foldreader.core.backup.webdav.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavCredentialStoreTest {

    private val store = WebDavCredentialStore(RuntimeEnvironment.getApplication())

    @Test
    fun `保存后可读回原值`() {
        store.savePassword("p@ss-口令")

        assertEquals("p@ss-口令", store.readPassword())
    }

    @Test
    fun `未保存时读取为 null`() {
        store.clear()

        assertNull(store.readPassword())
    }

    @Test
    fun `清除后读取为 null`() {
        store.savePassword("p@ss")
        store.clear()

        assertNull(store.readPassword())
    }

    @Test
    fun `覆盖保存读回最新值`() {
        store.savePassword("old")
        store.savePassword("new")

        assertEquals("new", store.readPassword())
    }

    @Test
    fun `密文不落明文——SharedPreferences 里读不回原密码`() {
        store.savePassword("p@ss-口令")

        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("webdav_credentials", android.content.Context.MODE_PRIVATE)
        val stored = prefs.getString("password", null)
        assertEquals(true, stored != null)
        assertEquals(false, stored!!.contains("p@ss-口令"))
    }
}
