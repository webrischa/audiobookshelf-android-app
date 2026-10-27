package com.audiobookshelf.nativeapp

import com.audiobookshelf.nativeapp.data.Repository
import com.audiobookshelf.nativeapp.ui.buildServerUrl
import com.audiobookshelf.nativeapp.ui.splitServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The login form joins a protocol picker with a host field, and the result is what every request
 * is built on. A double scheme ("http://https://x") or a missing one silently produced a server
 * that could never answer, so this is worth pinning down.
 */
class ServerUrlTest {

  @Test
  fun `joins protocol and host`() {
    assertEquals("https://abs.example.com", buildServerUrl("https", "abs.example.com"))
    assertEquals("http://192.168.1.10:13378", buildServerUrl("http", "192.168.1.10:13378"))
  }

  @Test
  fun `strips a scheme pasted into the host field`() {
    assertEquals("https://abs.example.com", buildServerUrl("https", "https://abs.example.com"))
    assertEquals("http://abs.example.com", buildServerUrl("http", "http://abs.example.com/"))
  }

  @Test
  fun `trims whitespace and trailing slashes`() {
    assertEquals("https://abs.example.com", buildServerUrl("https", "  abs.example.com//  "))
  }

  @Test
  fun `empty host produces an empty url`() {
    assertEquals("", buildServerUrl("https", "   "))
  }

  @Test
  fun `splits a stored url back into protocol and host`() {
    assertEquals("https" to "abs.example.com", splitServerUrl("https://abs.example.com"))
    assertEquals("http" to "192.168.1.10:8977", splitServerUrl("http://192.168.1.10:8977/"))
  }

  @Test
  fun `splitting without a scheme falls back to the requested default`() {
    assertEquals("http" to "abs.local", splitServerUrl("abs.local", defaultProtocol = "http"))
    assertEquals("https" to "", splitServerUrl(null))
  }

  @Test
  fun `normalizing adds a scheme and drops the trailing slash`() {
    assertEquals("http://abs.local", Repository.normalizeServer("abs.local/"))
    assertEquals("https://abs.local", Repository.normalizeServer("  https://abs.local  "))
    assertEquals("http://abs.local:13378", Repository.normalizeServer("abs.local:13378"))
  }

  @Test
  fun `normalizing an empty value stays empty`() {
    assertEquals("", Repository.normalizeServer("   "))
  }
}
