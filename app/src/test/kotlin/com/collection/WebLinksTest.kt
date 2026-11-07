package com.collection

import com.collection.domain.extractWebLinks
import org.junit.Assert.*
import org.junit.Test

class WebLinksTest {
    @Test fun extractsLinksFromProseAndMarkdownWithoutEatingBalancedParentheses() {
        val links = extractWebLinks("参考：https://example.com/a?q=1。另见 [维基](https://en.wikipedia.org/wiki/Compose_(software))，www.android.com。")
        assertEquals(listOf("example.com", "en.wikipedia.org", "android.com"), links.map { it.domain })
        assertEquals("https://en.wikipedia.org/wiki/Compose_(software)", links[1].url)
        assertEquals("https://www.android.com", links[2].url)
    }
    @Test fun detectsBareDomainsAndDeduplicatesRepeatedAddresses() {
        val links = extractWebLinks("Example.com https://example.com/ https://example.com/page https://example.com/page")
        assertEquals(listOf("https://example.com", "https://example.com/page"), links.map { it.url })
    }
    @Test fun ignoresEmailsCredentialsAndUnsupportedSchemes() {
        assertTrue(extractWebLinks("hello@example.com ftp://example.com/file javascript:alert(1) https://user:pass@example.com/a").isEmpty())
    }
    @Test fun supportsChineseHostAndPreservesQueryEncoding() {
        val link = extractWebLinks("https://例子.测试/path?a=%26%20#part").single()
        assertEquals("xn--fsqu00a.xn--0zwm56d", link.domain)
        assertTrue(link.url.endsWith("/path?a=%26%20#part"))
    }
    @Test fun rejectsInvalidPortAndDoesNotProduceEmptyResults() {
        assertTrue(extractWebLinks("https://example.com:99999/a https://example.com:bad/a 普通文字").isEmpty())
        assertEquals("http://example.com:8080/a", extractWebLinks("http://example.com:8080/a").single().url)
    }
}
