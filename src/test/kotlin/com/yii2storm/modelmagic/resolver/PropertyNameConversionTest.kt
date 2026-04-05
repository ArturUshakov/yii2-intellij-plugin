package com.yii2storm.modelmagic.resolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PropertyNameConversionTest {

    private val resolver = MagicPropertyResolver()

    @Test
    fun `getter names for snake_case include camel and direct variants`() {
        assertEquals(listOf("getUserId", "getUser_id"), resolver.getGetterNames("user_id"))
    }

    @Test
    fun `setter names for legacy mixed case include direct variant`() {
        assertEquals(listOf("setDirectionId", "setDirection_id"), resolver.getSetterNames("Direction_id"))
    }

    @Test
    fun `primary getter name uses camelized form`() {
        assertEquals("getFullName", resolver.propertyNameToGetter("full_name"))
    }

    @Test
    fun `primary setter name uses camelized form`() {
        assertEquals("setFullName", resolver.propertyNameToSetter("full_name"))
    }
}
