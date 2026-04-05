package com.yii2storm.modelmagic.resolver

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for property name conversion utilities.
 * These tests don't require IntelliJ Platform context.
 */
class PropertyNameConversionTest {

    @Test
    fun `test getter to property name - camelCase`() {
        assertEquals("fullName", convertGetterToProperty("getFullName"))
    }

    @Test
    fun `test getter to property name - snake_case`() {
        assertEquals("user_id", convertGetterToProperty("getUser_id"))
    }

    @Test
    fun `test getter to property name - simple`() {
        assertEquals("name", convertGetterToProperty("getName"))
    }

    @Test
    fun `test property name to getter - snake_case to camelCase`() {
        assertEquals("getUserId", convertPropertyToGetter("user_id"))
    }

    @Test
    fun `test property name to getter - camelCase`() {
        assertEquals("getFullName", convertPropertyToGetter("fullName"))
    }

    @Test
    fun `test property name to setter - snake_case to camelCase`() {
        assertEquals("setUserId", convertPropertyToSetter("user_id"))
    }

    @Test
    fun `test property name to setter - camelCase`() {
        assertEquals("setFullName", convertPropertyToSetter("fullName"))
    }

    // Helper functions that mirror the resolver's internal logic
    private fun convertGetterToProperty(getterName: String): String {
        val base = getterName.removePrefix("get")
        if (base.isEmpty()) return getterName
        return base.replaceFirstChar { it.lowercaseChar() }
    }

    private fun convertPropertyToGetter(propertyName: String): String {
        val camelCase = snakeToCamel(propertyName)
        return "get" + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    private fun convertPropertyToSetter(propertyName: String): String {
        val camelCase = snakeToCamel(propertyName)
        return "set" + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    private fun snakeToCamel(name: String): String {
        val parts = name.split("_")
        return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }
}
